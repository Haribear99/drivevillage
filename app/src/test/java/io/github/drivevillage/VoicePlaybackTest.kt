package io.github.drivevillage

import io.github.drivevillage.core.voice.FocusPort
import io.github.drivevillage.core.voice.PcmFormat
import io.github.drivevillage.core.voice.PcmSink
import io.github.drivevillage.core.voice.PlayOutcome
import io.github.drivevillage.core.voice.VoicePlayback
import io.github.drivevillage.core.voice.WavReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 목소리 재생의 중단·포커스 규칙을 경합 순서를 고정해 확인한다. */
class VoicePlaybackTest {

    private val fmt = PcmFormat(16_000, 1, 16)

    private class FakeSink(val onWrite: (Int) -> Unit = {}) : PcmSink {
        var opened = 0; var closed = 0; var chunks = 0; var drained = 0
        override fun open(format: PcmFormat): Boolean { opened++; return true }
        override fun write(buf: ByteArray, len: Int): Int { chunks++; onWrite(chunks); return len }
        override fun drain(stillValid: () -> Boolean) { drained++ }
        override fun close() { closed++ }
    }

    private class FakeFocus(var grant: Boolean = true, val onRequest: () -> Unit = {}) : FocusPort {
        var requests = 0; var abandons = 0
        override fun request(): Boolean { requests++; onRequest(); return grant }
        override fun abandon() { abandons++ }
    }

    private fun pcm(chunks: Int) = ByteArrayInputStream(ByteArray(chunks * 100))

    @Test fun normalPlaybackWritesAllDrainsAndReturnsFocus() {
        val p = VoicePlayback { true }
        val sink = FakeSink(); val focus = FakeFocus()
        assertEquals(PlayOutcome.DONE, p.play(p.supersede(), fmt, pcm(5), sink, focus, chunkBytes = 100))
        assertEquals(5, sink.chunks); assertEquals(1, sink.drained); assertEquals(1, sink.closed)
        assertEquals(1, focus.requests); assertEquals(1, focus.abandons)
    }

    @Test fun cancelBeforePlayNeverRequestsFocusOrOpensOutput() {
        val p = VoicePlayback { true }
        val token = p.supersede(); p.cancel()
        val sink = FakeSink(); val focus = FakeFocus()
        assertEquals(PlayOutcome.DROPPED, p.play(token, fmt, pcm(5), sink, focus))
        assertEquals(0, focus.requests); assertEquals(0, sink.opened)
    }

    @Test fun tripWhileWaitingForFocusDropsAndReturnsFocus() {
        var allowed = true
        val p = VoicePlayback { allowed }
        val sink = FakeSink()
        val focus = FakeFocus(onRequest = { allowed = false }) // 포커스를 얻는 사이 D 전환
        assertEquals(PlayOutcome.DROPPED, p.play(p.supersede(), fmt, pcm(5), sink, focus))
        assertEquals(0, sink.opened); assertEquals(1, focus.abandons)
    }

    @Test fun cancelMidStreamStopsBeforeNextChunk() {
        lateinit var p: VoicePlayback
        p = VoicePlayback { true }
        val sink = FakeSink(onWrite = { n -> if (n == 2) p.cancel() }) // 래치 콜백이 둘째 조각 직후 중단
        val focus = FakeFocus()
        assertEquals(PlayOutcome.STOPPED, p.play(p.supersede(), fmt, pcm(10), sink, focus, chunkBytes = 100))
        assertEquals("중단 뒤에는 한 조각도 더 쓰지 않는다", 2, sink.chunks)
        assertEquals(0, sink.drained); assertEquals(1, sink.closed); assertEquals(1, focus.abandons)
    }

    @Test fun becomingDisallowedMidStreamStops() {
        var allowed = true
        val p = VoicePlayback { allowed }
        val sink = FakeSink(onWrite = { n -> if (n == 3) allowed = false })
        assertEquals(PlayOutcome.STOPPED, p.play(p.supersede(), fmt, pcm(10), sink, FakeFocus(), chunkBytes = 100))
        assertEquals(3, sink.chunks)
    }

    @Test fun newerUtteranceInvalidatesOlderToken() {
        val p = VoicePlayback { true }
        val a = p.supersede(); val b = p.supersede()
        assertFalse(p.isValid(a)); assertTrue(p.isValid(b))
        assertEquals(PlayOutcome.DROPPED, p.play(a, fmt, pcm(3), FakeSink(), FakeFocus()))
    }

    @Test fun focusDeniedPlaysNothing() {
        val p = VoicePlayback { true }
        val sink = FakeSink()
        assertEquals(PlayOutcome.FOCUS_DENIED, p.play(p.supersede(), fmt, pcm(3), sink, FakeFocus(grant = false)))
        assertEquals(0, sink.opened)
    }

    @Test fun sequentialPlaysPairEveryFocusRequestWithOneAbandon() {
        val p = VoicePlayback { true }
        val focus = FakeFocus()
        repeat(3) { p.play(p.supersede(), fmt, pcm(2), FakeSink(), focus, chunkBytes = 100) }
        val t = p.supersede(); p.cancel(); p.play(t, fmt, pcm(2), FakeSink(), focus) // 요청 전 취소: 요청도 반납도 없음
        assertEquals(3, focus.requests); assertEquals(3, focus.abandons)
    }

    @Test fun cancelFromAnotherThreadIsSeenByPlaybackLoop() {
        val p = VoicePlayback { true }
        val token = p.supersede()
        val t = Thread { p.cancel() }; t.start(); t.join()
        assertFalse(p.isValid(token))
    }

    // ── WAV 머리 읽기 ──

    private fun wav(audioFormat: Int = 1, extraChunk: Boolean = false, bits: Int = 16, channels: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        fun le(n: Int, size: Int) = out.write(ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply { if (size == 2) putShort(n.toShort()) else putInt(n) }.array())
        out.write("RIFF".toByteArray()); le(0, 4); out.write("WAVE".toByteArray())
        if (extraChunk) { out.write("LIST".toByteArray()); le(3, 4); out.write(byteArrayOf(1, 2, 3, 0)) }
        out.write("fmt ".toByteArray()); le(16, 4); le(audioFormat, 2); le(channels, 2); le(22_050, 4); le(44_100, 4); le(2, 2); le(bits, 2)
        out.write("data".toByteArray()); le(4, 4); out.write(byteArrayOf(9, 8, 7, 6))
        return out.toByteArray()
    }

    @Test fun wavHeaderParsedAndStreamPositionedAtData() {
        val input = ByteArrayInputStream(wav(extraChunk = true))
        assertEquals(PcmFormat(22_050, 1, 16), WavReader.readHeader(input))
        assertEquals(9, input.read())
    }

    @Test fun nonPcmOrTruncatedWavRejected() {
        assertNull(WavReader.readHeader(ByteArrayInputStream(wav(audioFormat = 3))))
        // float 합성이 정수 PCM 표기(format=1)에 32비트로 기록된 경우: 16비트로 잘못 재생하지 않고 거부
        assertNull(WavReader.readHeader(ByteArrayInputStream(wav(bits = 32))))
        assertNull(WavReader.readHeader(ByteArrayInputStream(wav(channels = 6))))
        assertNull(WavReader.readHeader(ByteArrayInputStream("RIFF".toByteArray())))
        assertNull(WavReader.readHeader(ByteArrayInputStream("JUNKJUNKJUNK".toByteArray())))
    }
}
