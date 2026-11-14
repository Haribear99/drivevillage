package io.github.drivevillage

import android.content.Context
import androidx.compose.runtime.Composable
import io.github.drivevillage.vehicle.PleosVehicleSignals
import io.github.drivevillage.vehicle.VehicleSignals

/** pleos 빌드: 실제 차량 신호만 쓴다. 테스트 입력·배속·더미 데이터 없음. */
object Flavor {
    fun createSignals(context: Context): VehicleSignals = PleosVehicleSignals(context.applicationContext)

    @Composable
    fun SimulationOverlay(signals: VehicleSignals) = Unit
}
