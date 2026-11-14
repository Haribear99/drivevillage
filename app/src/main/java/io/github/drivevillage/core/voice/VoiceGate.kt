package io.github.drivevillage.core.voice

import io.github.drivevillage.core.gate.Screen

/**
 * 마을 음성의 허용 판정(순수 로직). 소리는 화면과 같은 fail-closed 규칙을 따른다.
 *
 * - 말할 수 있는 때: 마을 화면(P·UX 제한 없음 확인)이 열려 있고, 안전 래치가 끊기지 않았고, 사용자가 소리를 켜 둔 경우뿐.
 * - 그 밖의 모든 상태(대기·정적 안내·종료·래치 트립·소리 끔)에서는 새로 말하지 않고, 말하던 것도 즉시 멈춘다.
 * - 주민이 먼저 말을 거는 경로는 없다. 사용자가 연 화면에서 탑승·하차 한 문장만 읽는다.
 */
object VoiceGate {

    fun mayStart(screen: Screen, tripped: Boolean, enabled: Boolean, text: String?): Boolean =
        enabled && !tripped && screen == Screen.VILLAGE && !text.isNullOrBlank()

    /** 지금 들리는 소리를 끊어야 하는가. 시작 조건 중 하나라도 깨지면 참. */
    fun mustStop(screen: Screen, tripped: Boolean, enabled: Boolean): Boolean =
        !enabled || tripped || screen != Screen.VILLAGE
}
