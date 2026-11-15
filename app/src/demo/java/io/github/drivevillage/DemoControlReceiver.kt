package io.github.drivevillage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.drivevillage.core.gate.GearState

/**
 * demo 빌드 전용: 시연영상 녹화 때 adb로 시뮬레이션을 조작한다. pleos 빌드에는 없다(demo 소스셋·매니페스트).
 *
 * - capture 모드(오버레이 숨김): `am broadcast -p io.github.drivevillage.demo -a io.github.drivevillage.demo.CAPTURE --ez on true`
 * - 기어: `... -a io.github.drivevillage.demo.SIM --es gear D` (P 또는 D)
 * - 주행거리: `... -a io.github.drivevillage.demo.SIM --ei km 23`
 * - 날짜 이동: `... -a io.github.drivevillage.demo.SIM --ei days 1`
 */
class DemoControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sim = SimulatedSignals.get(context.applicationContext)
        when (intent.action) {
            ACTION_CAPTURE -> sim.setCapture(intent.getBooleanExtra("on", true))
            ACTION_SIM -> {
                when (intent.getStringExtra("gear")?.uppercase()) {
                    "P" -> sim.setGear(GearState.PARK)
                    "D" -> sim.setGear(GearState.NOT_PARK)
                }
                if (intent.hasExtra("km")) sim.addKm(intent.getIntExtra("km", 0).toDouble())
                if (intent.hasExtra("days")) sim.shiftDays(intent.getIntExtra("days", 0).toLong())
            }
        }
    }

    companion object {
        const val ACTION_CAPTURE = "io.github.drivevillage.demo.CAPTURE"
        const val ACTION_SIM = "io.github.drivevillage.demo.SIM"
    }
}
