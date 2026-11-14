package io.github.drivevillage.core.gate

/** 기어 신호. 값을 받지 못했거나 오류가 나면 UNKNOWN. */
enum class GearState { PARK, NOT_PARK, UNKNOWN }

/** CarUxRestrictions 상태. 서비스 연결 실패·미지원이면 UNKNOWN. */
enum class UxState { UNRESTRICTED, RESTRICTED, UNKNOWN }

enum class Screen {
    /** 앱을 연 직후 신호를 기다리는 중. 마을·버튼·소리 없는 빈 화면. */
    PENDING,
    /** 기어 P + UX 제한 없음이 모두 확인됨. */
    VILLAGE,
    /** 비P이지만 UX 제한 없음이 확인됨(예: 정차 D). 마을 없는 정적 안내 1줄. */
    STATIC_NOTICE,
    /** 앱 화면 종료. 플랫폼 차단 화면에 맡긴다. 종료 상태는 되돌아가지 않는다. */
    CLOSE,
}

/**
 * 표시 판정(fail-closed AND).
 * ① 기어 P와 ② UX 제한 없음이 모두 참일 때만 마을을 보인다.
 * 하나라도 제한·확인 불가면 닫는다. 주행거리 미증가는 주차 근거로 쓰지 않는다.
 */
object DisplayGate {
    fun decide(gear: GearState, ux: UxState): Screen = when {
        ux != UxState.UNRESTRICTED -> Screen.CLOSE
        gear == GearState.PARK -> Screen.VILLAGE
        gear == GearState.NOT_PARK -> Screen.STATIC_NOTICE
        else -> Screen.CLOSE
    }
}

/**
 * 한 번 연 화면(세션)의 상태기계.
 *
 * - PENDING: 두 신호가 모두 확인되면 판정한다. 제한이 오면 즉시 닫는다. 시간 초과면 닫는다.
 * - VILLAGE: 판정이 VILLAGE가 아니게 되는 순간 닫는다(정적 안내로 바꾸지 않음, 마지막 프레임 없이 숨김).
 * - STATIC_NOTICE: 닫힘 판정이면 닫는다. P가 돼도 마을로 자동 전환하지 않는다.
 * - CLOSE: 종료 상태.
 */
class GateSession {
    var gear: GearState = GearState.UNKNOWN
        private set
    var ux: UxState = UxState.UNKNOWN
        private set
    var screen: Screen = Screen.PENDING
        private set

    fun onGear(value: GearState): Screen { gear = value; return step() }
    fun onUx(value: UxState): Screen { ux = value; return step() }

    /** 신호 대기 시간이 끝났을 때 호출. 아직 판정하지 못했으면 닫는다. */
    fun onPendingTimeout(): Screen {
        if (screen == Screen.PENDING) screen = Screen.CLOSE
        return screen
    }

    /** 사용자가 [잘 자]를 눌렀거나 앱이 화면을 잃었을 때. */
    fun close(): Screen { screen = Screen.CLOSE; return screen }

    /** 차량 쓰기 직전 재확인. 열린 마을 화면에서 최신 값이 P·제한 없음일 때만 참. */
    fun canWrite(): Boolean =
        screen == Screen.VILLAGE && DisplayGate.decide(gear, ux) == Screen.VILLAGE

    private fun step(): Screen {
        val verdict = DisplayGate.decide(gear, ux)
        screen = when (screen) {
            Screen.PENDING -> when {
                ux == UxState.RESTRICTED -> Screen.CLOSE
                gear == GearState.UNKNOWN || ux == UxState.UNKNOWN -> Screen.PENDING
                else -> verdict
            }
            Screen.VILLAGE -> if (verdict == Screen.VILLAGE) Screen.VILLAGE else Screen.CLOSE
            Screen.STATIC_NOTICE -> if (verdict == Screen.CLOSE) Screen.CLOSE else Screen.STATIC_NOTICE
            Screen.CLOSE -> Screen.CLOSE
        }
        return screen
    }
}
