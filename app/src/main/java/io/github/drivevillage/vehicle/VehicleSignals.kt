package io.github.drivevillage.vehicle

import io.github.drivevillage.core.gate.GearState
import io.github.drivevillage.core.gate.SafetyLatch
import io.github.drivevillage.core.gate.UxState
import io.github.drivevillage.core.village.OdometerReading
import kotlinx.coroutines.flow.StateFlow

/**
 * 앱이 쓰는 차량 신호 전부(모두 normal 권한). 실제 구현은 AAOS GEAR_SELECTION·CarUxRestrictions + Pleos Odometer,
 * demo 빌드는 화면에 '시뮬레이션'을 표시하는 가짜 구현. 차량 쓰기(제어)는 하지 않는다.
 */
interface VehicleSignals {
    /**
     * 신호 콜백이 동기적으로 기록하는 안전 래치. 구현은 기어·UX 콜백과 오류·연결 상실을
     * 반드시 여기에 먼저 알린다.
     */
    val safety: SafetyLatch
    val gear: StateFlow<GearState>
    val ux: StateFlow<UxState>

    fun start()
    fun stop()

    suspend fun readOdometer(): OdometerReading

    /** 날짜 이동량(일). pleos는 늘 0(시험 입력 없음), demo는 시뮬레이션 패널의 '날짜 +30일'. */
    val dayShift: StateFlow<Long>

    /** 지금 시각(ms). pleos는 시스템 시각 그대로. */
    fun nowMillis(): Long = System.currentTimeMillis() + dayShift.value * DAY_MS

    companion object { const val DAY_MS = 24L * 60 * 60 * 1000 }
}
