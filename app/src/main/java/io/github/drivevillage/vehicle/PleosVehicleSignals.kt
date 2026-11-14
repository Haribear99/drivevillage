package io.github.drivevillage.vehicle

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import android.util.Log
import io.github.drivevillage.core.gate.GearState
import io.github.drivevillage.core.gate.SafetyLatch
import io.github.drivevillage.core.gate.UxState
import io.github.drivevillage.core.village.OdometerReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * 차량 신호 구현(읽기 전용).
 * - 기어·UX 제한: AAOS 표준([AaosCarWatcher]) — `GEAR_SELECTION`(CAR_POWERTRAIN), CarUxRestrictions.
 *   Pleos Vehicle SDK 2.3.2의 Gear API는 Deprecated("use the Google GEAR_SELECTION property")라 쓰지 않는다.
 * - 주행거리: Pleos SDK Odometer.getCurrentDistanceDriven(CAR_DRIVE)
 *
 * 안전 원칙
 * - 모든 Pleos SDK 호출은 전용 스레드(pleos-sdk)에서만. 메인 스레드를 막지 않는다.
 * - 기어·UX 값은 콜백 시점에 [safety]에 먼저 기록한다(StateFlow 합치기로 위험 신호를 놓치지 않게).
 * - 기어 구독이 실패하면 세션을 영구 종료한다(단발 조회로 대신하지 않음).
 * - 첫 연결 뒤 1초마다 기어를 직접 읽어 1.5초 안에 응답이 없으면 확인 불가로 바꾼다(연결 상실 감지).
 */
class PleosVehicleSignals(
    private val context: Context,
    private val car: AaosCarWatcher = AaosCarWatcher(context),
) : VehicleSignals {

    override val safety = SafetyLatch()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sdkExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "pleos-sdk") }
    @Volatile private var vehicle: Vehicle? = null
    @Volatile private var stopped = false

    override val gear: StateFlow<GearState> get() = car.gear
    override val ux: StateFlow<UxState> get() = car.ux
    /** 제출 빌드는 날짜를 바꾸지 않는다. */
    override val dayShift: StateFlow<Long> = MutableStateFlow(0L)

    override fun start() {
        car.start(
            onUx = safety::onUx,
            onGear = safety::onGear,
            onGearFailure = { safety.trip("gear-subscription-failed") },
        )
        // 생존 확인: 기어 확인 불가·무응답은 UNKNOWN(→ 무장 후라면 래치 작동, 화면 종료).
        scope.launch {
            while (isActive && !stopped) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (car.gear.value == GearState.UNKNOWN && !safety.isTripped) continue // 첫 값 전·확인 불가 중
                car.pollGear(HEARTBEAT_TIMEOUT_MS)
            }
        }
        // Pleos SDK: 생성·초기화를 SDK 스레드에서. 실패해도 화면 판정(기어·UX)과는 무관하다(주행거리만 '확인 불가').
        scope.launch {
            onSdk {
                if (stopped) return@onSdk Unit
                val v = try {
                    Vehicle(context).also { it.initialize() }
                } catch (t: Throwable) {
                    Log.w(TAG, "Vehicle SDK 초기화 실패: ${t.javaClass.simpleName}")
                    return@onSdk Unit
                }
                vehicle = v
                if (stopped) { runCatching { v.release() }; vehicle = null }
                Unit
            }
        }
    }

    override fun stop() {
        stopped = true
        safety.trip("stopped")
        scope.cancel()
        car.stop()
        runCatching {
            sdkExecutor.execute { vehicle?.let { v -> runCatching { v.release() } }; vehicle = null }
            sdkExecutor.shutdown() // 이미 넣은 해제 작업은 끝까지 실행된다.
        }
    }

    override suspend fun readOdometer(): OdometerReading {
        // SDK 초기화가 화면 판정보다 늦을 수 있어 잠시(최대 2초) 기다린다.
        var waited = 0L
        while (vehicle == null && waited < SDK_WAIT_MS && !stopped) { delay(100); waited += 100 }
        val v = vehicle ?: return OdometerReading.Failed("sdk-unavailable")
        val error = AtomicReference<String?>(null)
        val km = callback<Float> { ok, fail ->
            v.getOdometer().getCurrentDistanceDriven({ value: Float? -> ok(value) }, { e -> error.set(e.javaClass.simpleName); fail(e) })
        }
        return if (km == null) OdometerReading.Failed(error.get() ?: "timeout-or-null") else OdometerReading.Km(km.toDouble())
    }

    /** SDK 스레드에서 블록을 실행하고 결과를 받는다. stop() 이후면 null. */
    private suspend fun <T> onSdk(block: () -> T): T? = suspendCancellableCoroutine { cont ->
        try {
            sdkExecutor.execute {
                val r = try { block() } catch (t: Throwable) { null }
                if (cont.isActive) cont.resume(r)
            }
        } catch (e: RejectedExecutionException) {
            if (cont.isActive) cont.resume(null)
        }
    }

    /** SDK 콜백을 코루틴으로 바꾼다. 실패·시간 초과·null은 모두 null. 취소된 요청은 SDK를 부르지 않는다. */
    private suspend fun <T> callback(
        timeoutMs: Long = CALL_TIMEOUT_MS,
        block: (ok: (T?) -> Unit, fail: (Exception) -> Unit) -> Unit,
    ): T? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val settled = AtomicBoolean(false)
            cont.invokeOnCancellation { settled.set(true) }
            fun finish(value: T?) { if (settled.compareAndSet(false, true) && cont.isActive) cont.resume(value) }
            val ok: (T?) -> Unit = { finish(it) }
            val fail: (Exception) -> Unit = { e ->
                Log.w(TAG, "SDK 호출 실패: ${e.javaClass.simpleName}")
                finish(null)
            }
            try {
                sdkExecutor.execute {
                    if (settled.get() || stopped) return@execute
                    try { block(ok, fail) } catch (t: Throwable) {
                        Log.w(TAG, "SDK 호출 예외: ${t.javaClass.simpleName}")
                        finish(null)
                    }
                }
            } catch (e: RejectedExecutionException) {
                finish(null) // stop() 이후 호출
            }
        }
    }

    companion object {
        private const val TAG = "DriveVillage"
        private const val CALL_TIMEOUT_MS = 2_000L
        private const val SDK_WAIT_MS = 2_000L
        private const val HEARTBEAT_INTERVAL_MS = 1_000L
        private const val HEARTBEAT_TIMEOUT_MS = 1_500L
    }
}
