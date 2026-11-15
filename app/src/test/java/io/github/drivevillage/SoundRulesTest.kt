package io.github.drivevillage

import io.github.drivevillage.core.gate.SafetyLatch
import io.github.drivevillage.core.gate.GearState
import io.github.drivevillage.core.gate.UxState
import io.github.drivevillage.core.voice.FocusApi
import io.github.drivevillage.core.voice.SharedFocus
import io.github.drivevillage.core.voice.Sfx
import io.github.drivevillage.core.voice.SfxRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File

class SoundRulesTest {

    private class FakeFocus(var grant: Boolean = true) : FocusApi {
        var requests = 0
        var abandons = 0
        override fun request(): Boolean { requests++; return grant }
        override fun abandon() { abandons++ }
    }

    @Test fun voiceAndEffectsShareOneFocusRequestAndAbandonAfterLastHolder() {
        val api = FakeFocus()
        val f = SharedFocus(api)
        val voice = f.acquire()!!
        val tap = f.acquire()!!
        assertEquals("두 번째 소리는 다시 요청하지 않는다(자기 소리끼리 포커스를 뺏지 않게)", 1, api.requests)
        f.release(tap)
        assertEquals(0, api.abandons)
        assertTrue(f.isHeld())
        f.release(voice)
        assertEquals(1, api.abandons)
        assertFalse(f.isHeld())
        f.release(voice) // 같은 임대를 두 번 놓아도 반납이 두 번 일어나지 않는다
        assertEquals(1, api.abandons)
    }

    @Test fun deniedFocusMeansNoLeaseAndNoSound() {
        val api = FakeFocus(grant = false)
        val f = SharedFocus(api)
        assertNull(f.acquire())
        assertFalse(f.isHeld())
        assertEquals(0, api.abandons)
    }

    @Test fun effectsDoNotRequestFreshFocusWhileOtherMediaPlaysButJoinOwnHeldFocus() {
        val api = FakeFocus()
        val f = SharedFocus(api)
        var otherMedia = true
        assertNull("다른 앱 음악 중이면 효과음은 포커스를 새로 요청하지 않는다", f.acquire { !otherMedia })
        assertEquals(0, api.requests)
        val voice = f.acquire()!! // 음성은 그대로 요청
        assertNotNull("앱이 이미 포커스를 가진 동안에는 확인하지 않는다", f.acquire { !otherMedia })
        assertEquals(1, api.requests)
        f.release(voice)
        otherMedia = false
        assertNotNull(f.acquire { !otherMedia })
    }

    @Test fun focusLossInvalidatesOldLeasesSoLateReleaseDoesNotDropNewFocus() {
        val api = FakeFocus()
        val f = SharedFocus(api)
        val old = f.acquire()!!
        f.onLost()
        assertEquals("잃으면 바로 반납", 1, api.abandons)
        assertFalse(f.isHeld())
        val fresh = f.acquire()!!
        assertEquals(2, api.requests)
        f.release(old) // 잃기 전 임대의 늦은 반납은 무시
        assertTrue(f.isHeld())
        assertEquals(1, api.abandons)
        f.release(fresh)
        assertEquals(2, api.abandons)
    }

    @Test fun sfxRulesSuppressRapidRepeatsAndRotateVariants() {
        val r = SfxRules()
        assertEquals(0, r.admit(Sfx.TAP, 1_000))
        assertNull("80ms 안의 연타는 버린다", r.admit(Sfx.TAP, 1_050))
        assertEquals(1, r.admit(Sfx.TAP, 1_080))
        assertEquals(2, r.admit(Sfx.TAP, 1_200))
        assertEquals("변형은 돌아가며 쓰고 연달아 같은 것이 나오지 않는다", 0, r.admit(Sfx.TAP, 1_300))
        // 종류마다 따로 센다
        assertEquals(0, r.admit(Sfx.CANCEL, 1_301))
        assertEquals(0, r.admit(Sfx.CHIME, 1_302))
        assertNull(r.admit(Sfx.CHIME, 2_000))
        assertEquals(0, r.admit(Sfx.CHIME, 2_502))
    }

    @Test fun latchTripHookStopsAllSoundSynchronouslyOnGearD() {
        // 래치 콜백 하나에서 음성·효과음 정지를 모두 부른다(ViewModel의 onTrip과 같은 모양). 걸린 스레드에서 바로 실행되어야 한다.
        val latch = SafetyLatch()
        val stopped = mutableListOf<String>()
        latch.onTrip = { stopped += "voice"; stopped += "sfx" }
        latch.onGear(GearState.PARK); latch.onUx(UxState.UNRESTRICTED); latch.arm()
        assertTrue(stopped.isEmpty())
        latch.onGear(GearState.NOT_PARK)
        assertEquals(listOf("voice", "sfx"), stopped)
        latch.onGear(GearState.PARK) // 다시 P가 되어도 풀리지 않고 다시 부르지 않는다
        assertEquals(2, stopped.size)
        assertTrue(latch.isTripped)
    }

    /** tools/gen_sfx.py 결과물이 앱의 Sfx 표(종류·변형 수·길이)와 맞는지. 파일 길이는 0.1~0.8초. */
    @Test fun synthesizedAssetsMatchSfxTable() {
        val raw = listOf(File("src/main/res/raw"), File("app/src/main/res/raw")).first { it.isDirectory }
        for (s in Sfx.entries) {
            for (v in 1..s.variants) {
                val f = File(raw, "sfx_${s.fileBase}_$v.wav")
                assertTrue("${f.name} 없음", f.isFile)
                val ms = wavDurationMs(f)
                assertTrue("${f.name} ${ms}ms", ms in 100..800)
                assertTrue("${f.name} ${ms}ms > 표 ${s.durationMs}ms", ms <= s.durationMs)
            }
            assertFalse("변형 수보다 많은 파일이 남아 있으면 안 된다", File(raw, "sfx_${s.fileBase}_${s.variants + 1}.wav").exists())
        }
    }

    private fun wavDurationMs(f: File): Long {
        DataInputStream(f.inputStream().buffered()).use { d ->
            val b = ByteArray(44)
            d.readFully(b)
            fun le32(o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
            fun le16(o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
            assertEquals("RIFF", String(b, 0, 4))
            assertEquals("PCM", 1, le16(20))
            assertEquals("모노", 1, le16(22))
            val rate = le32(24)
            val bytesPerFrame = le16(32)
            assertEquals("data", String(b, 36, 4))
            return le32(40).toLong() / bytesPerFrame * 1000 / rate
        }
    }
}
