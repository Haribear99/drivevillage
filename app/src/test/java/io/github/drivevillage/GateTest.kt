package io.github.drivevillage

import io.github.drivevillage.core.gate.DisplayGate
import io.github.drivevillage.core.gate.GateSession
import io.github.drivevillage.core.gate.GearState
import io.github.drivevillage.core.gate.Screen
import io.github.drivevillage.core.gate.UxState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateTest {

    /** 기어 3 × UX 3 = 9개 조합 전수. VILLAGE는 (P, 제한 없음) 하나뿐. */
    @Test fun decide_allNineCombinations() {
        for (g in GearState.entries) for (u in UxState.entries) {
            val expected = when {
                u != UxState.UNRESTRICTED -> Screen.CLOSE
                g == GearState.PARK -> Screen.VILLAGE
                g == GearState.NOT_PARK -> Screen.STATIC_NOTICE
                else -> Screen.CLOSE
            }
            assertEquals("$g/$u", expected, DisplayGate.decide(g, u))
        }
        val villages = GearState.entries.flatMap { g -> UxState.entries.map { u -> DisplayGate.decide(g, u) } }.count { it == Screen.VILLAGE }
        assertEquals(1, villages)
    }

    @Test fun pending_untilBothKnown_thenVillage() {
        val s = GateSession()
        assertEquals(Screen.PENDING, s.onGear(GearState.PARK))
        assertFalse(s.canWrite())
        assertEquals(Screen.VILLAGE, s.onUx(UxState.UNRESTRICTED))
        assertTrue(s.canWrite())
    }

    @Test fun pending_restrictionClosesImmediately() {
        val s = GateSession()
        assertEquals(Screen.CLOSE, s.onUx(UxState.RESTRICTED))
        assertEquals(Screen.CLOSE, s.onGear(GearState.PARK)) // 종료는 되돌아가지 않음
        assertEquals(Screen.CLOSE, s.onUx(UxState.UNRESTRICTED))
    }

    @Test fun pending_timeoutWithMissingValueCloses() {
        val s = GateSession()
        s.onGear(GearState.PARK) // UX 미수신
        assertEquals(Screen.CLOSE, s.onPendingTimeout())
        val t = GateSession()
        t.onUx(UxState.UNRESTRICTED) // 기어 미수신
        assertEquals(Screen.CLOSE, t.onPendingTimeout())
    }

    @Test fun pending_timeoutAfterDecisionKeepsScreen() {
        val s = GateSession()
        s.onGear(GearState.PARK); s.onUx(UxState.UNRESTRICTED)
        assertEquals(Screen.VILLAGE, s.onPendingTimeout())
    }

    @Test fun village_anyLossCloses() {
        val events = listOf<(GateSession) -> Screen>(
            { it.onGear(GearState.NOT_PARK) },
            { it.onGear(GearState.UNKNOWN) },
            { it.onUx(UxState.RESTRICTED) },
            { it.onUx(UxState.UNKNOWN) },
        )
        for (event in events) {
            val s = GateSession(); s.onGear(GearState.PARK); s.onUx(UxState.UNRESTRICTED)
            assertEquals(Screen.CLOSE, event(s))
            assertFalse(s.canWrite())
            // 닫힌 뒤 다시 P가 와도 저절로 돌아오지 않는다.
            assertEquals(Screen.CLOSE, s.onGear(GearState.PARK))
        }
    }

    @Test fun staticNotice_doesNotAutoSwitchToVillage() {
        val s = GateSession()
        s.onGear(GearState.NOT_PARK)
        assertEquals(Screen.STATIC_NOTICE, s.onUx(UxState.UNRESTRICTED))
        assertEquals(Screen.STATIC_NOTICE, s.onGear(GearState.PARK))
        assertFalse(s.canWrite())
        assertEquals(Screen.CLOSE, s.onUx(UxState.RESTRICTED))
    }

    @Test fun close_isTerminal_andBlocksWrite() {
        val s = GateSession(); s.onGear(GearState.PARK); s.onUx(UxState.UNRESTRICTED)
        s.close()
        assertFalse(s.canWrite())
        assertEquals(Screen.CLOSE, s.onUx(UxState.UNRESTRICTED))
    }
}
