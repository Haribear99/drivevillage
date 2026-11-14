package io.github.drivevillage

import io.github.drivevillage.core.gate.Screen
import io.github.drivevillage.core.voice.VoiceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceGateTest {

    @Test fun speaksOnlyOnVillageScreenWithLatchIntactAndVoiceOn() {
        assertTrue(VoiceGate.mayStart(Screen.VILLAGE, tripped = false, enabled = true, text = "안녕"))
        // 화면 판정 4상태 중 VILLAGE만 허용
        for (s in listOf(Screen.PENDING, Screen.STATIC_NOTICE, Screen.CLOSE)) {
            assertFalse(s.name, VoiceGate.mayStart(s, tripped = false, enabled = true, text = "안녕"))
        }
        assertFalse(VoiceGate.mayStart(Screen.VILLAGE, tripped = true, enabled = true, text = "안녕"))
        assertFalse(VoiceGate.mayStart(Screen.VILLAGE, tripped = false, enabled = false, text = "안녕"))
        assertFalse(VoiceGate.mayStart(Screen.VILLAGE, tripped = false, enabled = true, text = null))
        assertFalse(VoiceGate.mayStart(Screen.VILLAGE, tripped = false, enabled = true, text = "  "))
    }

    @Test fun mustStopWheneverAnyStartConditionBreaks() {
        assertFalse(VoiceGate.mustStop(Screen.VILLAGE, tripped = false, enabled = true))
        // 모든 조합: VILLAGE·래치 정상·켜짐이 아닌 경우는 전부 멈춤
        for (s in Screen.values()) for (t in listOf(false, true)) for (e in listOf(false, true)) {
            val expectStop = !(s == Screen.VILLAGE && !t && e)
            assertEquals("$s/$t/$e", expectStop, VoiceGate.mustStop(s, t, e))
        }
    }


}
