package io.github.drivevillage

import io.github.drivevillage.core.voice.SfxPlayback
import io.github.drivevillage.core.voice.SfxPort
import io.github.drivevillage.core.voice.SfxStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 효과음 세대·재확인 규칙: 래치(stopAll·허용 조건 상실)가 play 직전·직후·재생 중 어디에 끼어도 소리가 남지 않고 임대는 한 번만 반납된다. */
class SfxPlaybackTest {

    private class FakePort : SfxPort<String> {
        var acquires = 0
        var grant = true
        val released = mutableListOf<String>()
        val played = mutableListOf<Int>()
        val stopped = mutableListOf<Int>()
        var nextStream = 7
        var onAcquire: () -> Unit = {}
        var onPlay: () -> Unit = {}
        override fun acquire(): String? { acquires++; onAcquire(); return if (grant) "lease-$acquires" else null }
        override fun release(lease: String) { released += lease }
        override fun play(sample: Int): Int { played += sample; onPlay(); return nextStream }
        override fun stop(stream: Int) { stopped += stream }
    }

    private var allowed = true
    private val port = FakePort()
    private val sfx = SfxPlayback(port) { allowed }

    @Test fun normalPlay_registersAndFinishReleasesOnce() {
        val (outcome, p) = sfx.start(sample = 3, token = sfx.token())
        assertEquals(SfxStart.PLAYING, outcome)
        assertNotNull(p)
        assertEquals(listOf(3), port.played)
        assertEquals(1, sfx.activeCount())
        sfx.finish(p!!)
        sfx.finish(p) // 예약된 끝내기와 stopAll의 끝내기가 겹쳐도 반납은 한 번
        assertEquals(listOf("lease-1"), port.released)
        assertEquals(0, sfx.activeCount())
    }

    @Test fun latchJustBeforePlay_stopAll_noPlayAndLeaseReleased() {
        // 포커스를 받는 사이(첫 확인 뒤, play 전)에 래치 콜백이 stopAll을 부른다.
        port.onAcquire = { sfx.stopAll() }
        val (outcome, p) = sfx.start(sample = 3, token = sfx.token())
        assertEquals(SfxStart.DROPPED, outcome)
        assertNull(p)
        assertTrue("play가 불리면 안 된다", port.played.isEmpty())
        assertEquals(listOf("lease-1"), port.released)
        assertEquals(0, sfx.activeCount())
    }

    @Test fun latchJustBeforePlay_allowedLost_noPlayAndLeaseReleased() {
        // stopAll보다 먼저 허용 조건(래치 걸림)이 거짓이 된 경우도 같은 자리에서 막힌다.
        port.onAcquire = { allowed = false }
        val (outcome, _) = sfx.start(sample = 3, token = sfx.token())
        assertEquals(SfxStart.DROPPED, outcome)
        assertTrue(port.played.isEmpty())
        assertEquals(1, port.released.size)
    }

    @Test fun latchJustAfterPlay_beforeRegistration_streamStoppedImmediately() {
        // play가 막 돌아오는 순간 stopAll: 그때는 아직 등록 전이라 stopAll이 이 스트림을 못 본다. play 직후 재확인이 멈춘다.
        var stopAllSaw = -1
        port.onPlay = { stopAllSaw = sfx.stopAll().size }
        val (outcome, p) = sfx.start(sample = 3, token = sfx.token())
        assertEquals(0, stopAllSaw)
        assertEquals(SfxStart.RACED, outcome)
        assertNull(p)
        assertEquals(listOf(7), port.stopped)
        assertEquals(listOf("lease-1"), port.released)
        assertEquals(0, sfx.activeCount())
    }

    @Test fun latchWhilePlaying_stopAllStopsRegisteredStream_thenFinishOnce() {
        val (_, p) = sfx.start(sample = 3, token = sfx.token())
        val playing = sfx.stopAll()
        assertEquals(listOf(7), port.stopped)
        assertEquals(listOf(p), playing)
        playing.forEach { sfx.finish(it) }
        sfx.finish(p!!) // 늦게 도착한 예약 끝내기
        assertEquals(1, port.released.size)
    }

    @Test fun staleTokenOrShutdown_dropsWithoutTouchingFocus() {
        val old = sfx.token()
        sfx.stopAll()
        assertEquals(SfxStart.DROPPED, sfx.start(3, old).first)
        sfx.markShutdown()
        assertEquals(SfxStart.DROPPED, sfx.start(3, sfx.token()).first)
        assertEquals(0, port.acquires)
        assertTrue(port.played.isEmpty())
    }

    @Test fun focusDeniedOrPlayFailed_noLeak() {
        port.grant = false
        assertEquals(SfxStart.FOCUS_DENIED, sfx.start(3, sfx.token()).first)
        port.grant = true
        port.nextStream = 0
        assertEquals(SfxStart.FAILED, sfx.start(3, sfx.token()).first)
        assertEquals(listOf("lease-2"), port.released)
        assertEquals(0, sfx.activeCount())
    }
}
