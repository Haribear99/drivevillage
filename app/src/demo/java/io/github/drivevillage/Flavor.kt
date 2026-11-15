package io.github.drivevillage

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.drivevillage.core.gate.GearState
import io.github.drivevillage.core.gate.SafetyLatch
import io.github.drivevillage.core.gate.UxState
import io.github.drivevillage.core.village.OdometerReading
import io.github.drivevillage.vehicle.VehicleSignals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * demo 빌드: 차량 없이 화면을 보기 위한 시뮬레이션. 화면 상단에 늘 '시뮬레이션'을 표시한다.
 * 제출용 pleos 빌드에는 이 코드가 들어가지 않는다(flavor 소스셋 분리).
 */
object Flavor {
    fun createSignals(context: Context): VehicleSignals = SimulatedSignals.get(context.applicationContext)

    @Composable
    fun SimulationOverlay(signals: VehicleSignals) {
        val sim = signals as? SimulatedSignals ?: return
        // 시연영상 녹화용 capture 모드: 오버레이를 숨긴다(adb로 켜고 끔, DemoControlReceiver).
        val capture by sim.capture.collectAsState()
        if (capture) return
        var expanded by remember { mutableStateOf(false) }
        val gear by sim.gear.collectAsState()
        val shift by sim.dayShift.collectAsState()
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .background(Color(0xE0202020), RoundedCornerShape(10.dp)).padding(6.dp)
                    .width(if (expanded) 330.dp else 150.dp),
            ) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text("시뮬레이션 ${if (expanded) "▼" else "▲"}", color = Color(0xFFFFC94D), fontSize = 14.sp)
                }
                if (expanded) {
                    Text("시뮬레이션 신호 · 기어 $gear · ${"%.0f".format(sim.odometerKm)}km · 날짜 +${shift}일", color = Color.White, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SimButton("기어 P") { sim.setGear(GearState.PARK) }
                        SimButton("기어 D") { sim.setGear(GearState.NOT_PARK) }
                        SimButton("+1km") { sim.addKm(1.0) }
                        SimButton("+10km") { sim.addKm(10.0) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SimButton("날짜 +30일") { sim.shiftDays(30) }
                    }
                }
            }
        }
    }

    @Composable
    private fun SimButton(label: String, onClick: () -> Unit) {
        FilledTonalButton(onClick = onClick) { Text(label, fontSize = 12.sp) }
    }
}

/**
 * 프로세스 안에서 하나만 쓰는 가짜 차량(시뮬레이션). 주행거리·날짜 이동은 SharedPreferences에 남겨
 * 앱을 다시 열었을 때의 정산을 재현한다. 다시 열면 기어는 P에서 시작한다.
 */
class SimulatedSignals private constructor(context: Context) : VehicleSignals {
    private val prefs = context.getSharedPreferences("simulation", Context.MODE_PRIVATE)

    /** 열 때마다(세션마다) 새 래치. 래치는 한 세션 안에서만 되돌릴 수 없다. */
    override var safety = SafetyLatch()
        private set
    private val _gear = MutableStateFlow(GearState.UNKNOWN)
    override val gear: StateFlow<GearState> = _gear
    private val _ux = MutableStateFlow(UxState.UNKNOWN)
    override val ux: StateFlow<UxState> = _ux
    private val _dayShift = MutableStateFlow(prefs.getLong("dayShift", 0L))
    override val dayShift: StateFlow<Long> = _dayShift
    private val _capture = MutableStateFlow(prefs.getBoolean("capture", false))
    val capture: StateFlow<Boolean> = _capture

    private var odoState by mutableStateOf(prefs.getFloat("odo", 12_000f).toDouble())
    var odometerKm: Double
        get() = odoState
        private set(v) { odoState = v; prefs.edit().putFloat("odo", v.toFloat()).apply() }

    override fun start() {
        safety = SafetyLatch()
        setGear(GearState.PARK)
        setUx(UxState.UNRESTRICTED)
    }

    override fun stop() = Unit

    fun setGear(g: GearState) {
        safety.onGear(g)
        _gear.value = g
        // 실제 차량의 주행 제한을 흉내 낸다: P가 아니면 UX 제한.
        setUx(if (g == GearState.PARK) UxState.UNRESTRICTED else UxState.RESTRICTED)
    }
    private fun setUx(u: UxState) { safety.onUx(u); _ux.value = u }
    fun addKm(km: Double) { odometerKm += km }
    fun shiftDays(days: Long) {
        _dayShift.value += days
        prefs.edit().putLong("dayShift", _dayShift.value).apply()
    }
    fun setCapture(on: Boolean) {
        _capture.value = on
        prefs.edit().putBoolean("capture", on).apply()
    }

    override suspend fun readOdometer(): OdometerReading { delay(150); return OdometerReading.Km(odometerKm) }

    companion object {
        @Volatile private var instance: SimulatedSignals? = null
        fun get(context: Context): SimulatedSignals =
            instance ?: synchronized(this) { instance ?: SimulatedSignals(context).also { instance = it } }
    }
}
