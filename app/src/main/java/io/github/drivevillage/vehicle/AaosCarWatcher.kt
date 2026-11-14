package io.github.drivevillage.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import io.github.drivevillage.core.gate.GearState
import io.github.drivevillage.core.gate.UxState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * AAOS 표준 신호 두 가지를 한 Car 연결에서 받는다(fail-closed).
 * - 기어: `VehiclePropertyIds.GEAR_SELECTION`(권한 android.car.permission.CAR_POWERTRAIN).
 *   Pleos Vehicle SDK 2.3.2의 Gear API는 "use the Google GEAR_SELECTION property"로 Deprecated다.
 * - UX 제한: CarUxRestrictions. '제한 없음'은 isRequiresDistractionOptimization == false일 때만.
 *
 * 스레드·경합 원칙
 * - Car 연결은 비동기(CAR_WAIT_TIMEOUT_DO_NOT_WAIT). lifecycle 콜백(메인)에서는 기록·예약만 하고
 *   연결·구독은 전용 스레드(car-ux)에서 한다. 안전 이벤트(기어·UX 콜백)가 이 스레드로 오므로,
 *   수초 걸릴 수 있는 동기 조회(getProperty)는 별도 스레드(car-poll)에서만 한다.
 *   그래서 조회가 막혀도 D·제한 이벤트는 바로 래치에 기록된다.
 * - 연결 세대([generation])와 [publishLock]으로 '유효성 확인 → 게시'를 원자화한다. 연결 상실·종료 시
 *   세대를 올리고 UNKNOWN을 같은 잠금 안에서 기록하므로, 이전 연결의 늦은 결과가 덮지 못한다.
 * - 기어 이벤트에는 순번을 매긴다. 생존 확인 조회를 시작한 뒤 이벤트가 오면 그 조회의 값은 버린다.
 * - 기어 구독 실패(등록 실패·onErrorEvent)는 [onGearFailure]로 알린다(세션 영구 종료 사유).
 */
class AaosCarWatcher(private val context: Context) {

    private val _ux = MutableStateFlow(UxState.UNKNOWN)
    val ux: StateFlow<UxState> = _ux
    private val _gear = MutableStateFlow(GearState.UNKNOWN)
    val gear: StateFlow<GearState> = _gear

    @Volatile private var stopped = false
    private var onUx: (UxState) -> Unit = {}
    private var onGear: (GearState) -> Unit = {}
    private var onGearFailure: () -> Unit = {}

    @Volatile private var car: Car? = null
    @Volatile private var uxManager: CarUxRestrictionsManager? = null
    @Volatile private var propManager: CarPropertyManager? = null
    @Volatile private var gearCallback: CarPropertyManager.CarPropertyEventCallback? = null
    @Volatile private var thread: HandlerThread? = null
    @Volatile private var worker: Handler? = null
    /** 동기 Binder 조회 전용(안전 이벤트 스레드와 분리). */
    @Volatile private var pollThread: HandlerThread? = null
    @Volatile private var poller: Handler? = null

    private val publishLock = Any()
    @Volatile private var generation = 0
    private var gearEventSeq = 0L
    /** 마지막으로 기어 P를 확인한 시각(nanoTime). 쓰기 허용의 신선도 조건. */
    @Volatile private var lastParkConfirmedAt = 0L

    fun start(onUx: (UxState) -> Unit, onGear: (GearState) -> Unit, onGearFailure: () -> Unit) {
        this.onUx = onUx
        this.onGear = onGear
        this.onGearFailure = onGearFailure
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        ) {
            synchronized(publishLock) { setUx(UxState.UNKNOWN); setGear(GearState.UNKNOWN) }
            return
        }
        try {
            val t = HandlerThread("car-ux").also { it.start() }
            thread = t
            val pt = HandlerThread("car-poll").also { it.start() }
            pollThread = pt
            poller = Handler(pt.looper)
            val w = Handler(t.looper)
            worker = w
            car = Car.createCar(context, w, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                val gen = synchronized(publishLock) {
                    if (stopped) return@createCar
                    val g = ++generation
                    if (!ready) { setUx(UxState.UNKNOWN); setGear(GearState.UNKNOWN) }
                    g
                }
                if (!ready) {
                    w.post { cleanupManagers() }
                    return@createCar
                }
                w.post { connect(c, gen) }
            }
        } catch (t: Throwable) {
            // NoClassDefFoundError(android.car 미탑재) 포함
            Log.w(TAG, "Car 연결 불가: ${t.javaClass.simpleName}")
            synchronized(publishLock) { setUx(UxState.UNKNOWN); setGear(GearState.UNKNOWN) }
        }
    }

    /** car-ux 스레드에서 실행. */
    private fun connect(c: Car, gen: Int) {
        if (stopped || gen != generation) return
        // UX 제한
        try {
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            if (m == null) publishUx(gen, UxState.UNKNOWN) else {
                uxManager = m
                m.registerListener(CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r -> publishUx(gen, mapUx(r)) })
                publishUx(gen, mapUx(m.currentCarUxRestrictions))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "UX 제한 구독 실패: ${t.javaClass.simpleName}")
            publishUx(gen, UxState.UNKNOWN)
        }
        // 기어(GEAR_SELECTION)
        try {
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            if (pm == null) { gearFailed(gen, "no-property-manager"); return }
            propManager = pm
            val cb = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    if (value.propertyId == VehiclePropertyIds.GEAR_SELECTION) gearEvent(gen, mapGear(value))
                }
                override fun onErrorEvent(propertyId: Int, areaId: Int) {
                    if (propertyId == VehiclePropertyIds.GEAR_SELECTION) gearFailed(gen, "error-event")
                }
            }
            gearCallback = cb
            val ok = pm.registerCallback(cb, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            if (!ok) { gearFailed(gen, "register-false"); return }
            // ONCHANGE 구독은 보통 현재 값을 바로 보내지만, 보장되지 않으므로 한 번 직접 읽는다(조회 스레드에서).
            val seq = currentSeq()
            poller?.post { applyPolled(gen, seq, readGear(pm)) }
        } catch (t: Throwable) {
            Log.w(TAG, "기어 구독 실패: ${t.javaClass.simpleName}")
            gearFailed(gen, "exception")
        }
    }

    /**
     * 생존 확인: car-ux 스레드에서 기어를 직접 읽는다. [timeoutMs] 안에 응답이 없거나 읽기에 실패하면
     * UNKNOWN을 기록한다(→ 무장 후라면 래치 작동). 조회 중 이벤트가 오면 조회 값은 버린다.
     */
    suspend fun pollGear(timeoutMs: Long) {
        val w = poller ?: return
        val gen = generation
        val seq = currentSeq()
        val answered = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val once = AtomicBoolean(false)
                val posted = w.post {
                    val pm = propManager
                    val value = if (pm == null) GearState.UNKNOWN else readGear(pm)
                    if (once.compareAndSet(false, true) && cont.isActive) cont.resume(value)
                }
                if (!posted && once.compareAndSet(false, true)) cont.resume(GearState.UNKNOWN)
            }
        }
        applyPolled(gen, seq, answered ?: GearState.UNKNOWN)
    }

    /** 최근 [freshMs] 안에 P를 확인했는지. */
    fun parkIsFresh(freshMs: Long): Boolean {
        val t = lastParkConfirmedAt
        return t != 0L && (System.nanoTime() - t) / 1_000_000 <= freshMs
    }

    fun stop() {
        synchronized(publishLock) {
            stopped = true
            generation++
            setUx(UxState.UNKNOWN)
            setGear(GearState.UNKNOWN)
        }
        // Binder 정리는 car-ux 스레드에서(메인 스레드를 막지 않게). 스레드가 없으면 여기서 바로.
        val cleanup = Runnable {
            cleanupManagers()
            runCatching { car?.disconnect() }
            car = null
            thread?.quitSafely()
            thread = null
            worker = null
            pollThread?.quitSafely()
            pollThread = null
            poller = null
        }
        val w = worker
        if (w == null || !w.post(cleanup)) cleanup.run()
    }

    private fun cleanupManagers() {
        runCatching { uxManager?.unregisterListener() }
        runCatching { gearCallback?.let { propManager?.unregisterCallback(it) } }
        uxManager = null
        propManager = null
        gearCallback = null
    }

    // ── 게시(모두 publishLock 안) ──────────────────────────────

    private fun publishUx(gen: Int, value: UxState) = synchronized(publishLock) {
        if (!stopped && gen == generation) setUx(value)
    }

    private fun gearEvent(gen: Int, value: GearState) = synchronized(publishLock) {
        if (stopped || gen != generation) return@synchronized
        gearEventSeq++
        if (value == GearState.PARK) lastParkConfirmedAt = System.nanoTime()
        setGear(value)
    }

    private fun currentSeq(): Long = synchronized(publishLock) { gearEventSeq }

    /** 조회 결과: 실패·UNKNOWN은 늘 반영(보수적), 값은 조회 시작 뒤 이벤트가 없을 때만. */
    private fun applyPolled(gen: Int, seq: Long, value: GearState) = synchronized(publishLock) {
        if (stopped || gen != generation) return@synchronized
        if (value == GearState.UNKNOWN) { setGear(GearState.UNKNOWN); return@synchronized }
        if (seq != gearEventSeq) return@synchronized
        if (value == GearState.PARK) lastParkConfirmedAt = System.nanoTime()
        if (_gear.value != value) setGear(value)
    }

    private fun gearFailed(gen: Int, why: String) {
        Log.w(TAG, "기어 신호 실패: $why")
        synchronized(publishLock) {
            if (stopped || gen != generation) return
            setGear(GearState.UNKNOWN)
        }
        onGearFailure()
    }

    private fun setUx(value: UxState) { onUx(value); _ux.value = value }       // 래치에 먼저(동기)
    private fun setGear(value: GearState) { onGear(value); _gear.value = value } // 래치에 먼저(동기)

    private fun readGear(pm: CarPropertyManager): GearState = try {
        mapGear(pm.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0))
    } catch (t: Throwable) {
        GearState.UNKNOWN
    }

    private fun mapGear(v: CarPropertyValue<*>?): GearState {
        if (v == null || v.status != CarPropertyValue.STATUS_AVAILABLE) return GearState.UNKNOWN
        return when ((v.value as? Int) ?: return GearState.UNKNOWN) {
            VehicleGear.GEAR_PARK -> GearState.PARK
            VehicleGear.GEAR_UNKNOWN -> GearState.UNKNOWN
            else -> GearState.NOT_PARK
        }
    }

    private fun mapUx(r: CarUxRestrictions?): UxState = when {
        r == null -> UxState.UNKNOWN
        r.isRequiresDistractionOptimization -> UxState.RESTRICTED
        else -> UxState.UNRESTRICTED
    }

    private companion object { const val TAG = "DriveVillage" }
}
