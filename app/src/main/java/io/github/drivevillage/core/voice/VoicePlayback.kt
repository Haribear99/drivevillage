package io.github.drivevillage.core.voice

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/** 재생 출력(실기기에서는 AudioTrack). 재생 스레드에서만 쓴다. */
interface PcmSink {
    fun open(format: PcmFormat): Boolean
    /** 한 조각을 쓴다. 실패하면 음수. */
    fun write(buf: ByteArray, len: Int): Int
    /** 다 쓴 뒤 버퍼에 남은 소리가 끝날 때까지 기다린다. [stillValid]가 거짓이 되면 바로 돌아간다. */
    fun drain(stillValid: () -> Boolean)
    fun close()
}

/** 오디오 포커스. 재생 스레드에서만 요청·반납한다(소유자는 언제나 현재 재생 하나). */
interface FocusPort {
    fun request(): Boolean
    fun abandon()
}

data class PcmFormat(val sampleRate: Int, val channels: Int, val bitsPerSample: Int)

enum class PlayOutcome { DONE, DROPPED, STOPPED, FOCUS_DENIED, FAILED }

/**
 * 마을 목소리 재생의 안전 규칙(순수 로직, 잠금 없음).
 *
 * - 모든 발화는 [supersede]로 받은 토큰을 가진다. [cancel]·새 발화는 세대를 올려 이전 토큰을 무효로 만든다.
 *   [cancel]은 원자 증가 하나라 어느 스레드(차량 신호 콜백 포함)에서도 기다림 없이 끝난다.
 * - [play]는 포커스 요청 전·후, 출력을 연 뒤, **매 조각을 쓰기 전**에 토큰과 [allowedNow]를 다시 확인한다.
 *   그래서 중단 신호 이후 새로 쓰이는 소리는 없다(이미 출력 버퍼에 들어간 소리는 호출자가 출력을 멈춰 비운다).
 * - 포커스는 재생 하나가 요청하고 같은 재생이 끝날 때 반납한다(성공·중단·실패 모두). 재생은 한 스레드에서 차례로만 돈다.
 */
class VoicePlayback(private val allowedNow: () -> Boolean) {

    private val generation = AtomicLong()

    fun current(): Long = generation.get()

    /** 새 발화 토큰. 이전 발화는 무효가 된다. */
    fun supersede(): Long = generation.incrementAndGet()

    /** 진행 중·대기 중인 모든 발화를 무효로 만든다. 잠금·대기 없음. */
    fun cancel() { generation.incrementAndGet() }

    fun isValid(token: Long): Boolean = generation.get() == token && allowedNow()

    fun play(token: Long, format: PcmFormat, pcm: InputStream, sink: PcmSink, focus: FocusPort, chunkBytes: Int = 2048): PlayOutcome {
        if (!isValid(token)) return PlayOutcome.DROPPED
        if (!focus.request()) return PlayOutcome.FOCUS_DENIED
        var opened = false
        try {
            if (!isValid(token)) return PlayOutcome.DROPPED
            if (!sink.open(format)) return PlayOutcome.FAILED
            opened = true
            val buf = ByteArray(chunkBytes)
            while (true) {
                if (!isValid(token)) return PlayOutcome.STOPPED
                val n = pcm.read(buf)
                if (n <= 0) {
                    sink.drain { isValid(token) }
                    return if (isValid(token)) PlayOutcome.DONE else PlayOutcome.STOPPED
                }
                if (!isValid(token)) return PlayOutcome.STOPPED
                if (sink.write(buf, n) < 0) return PlayOutcome.FAILED
            }
        } finally {
            if (opened) runCatching { sink.close() }
            runCatching { focus.abandon() }
        }
    }
}

/**
 * TTS가 만든 WAV(RIFF, PCM)의 형식을 읽고 데이터 시작 위치까지 넘긴다.
 * 재생할 수 있는 형식(정수 PCM 8·16비트, 모노·스테레오)이 아니면 null — 32비트(float 합성 등)는 잘못 재생하지 않고 거부한다.
 */
object WavReader {
    fun readHeader(input: InputStream): PcmFormat? {
        val d = DataInputStream(input)
        try {
            if (tag(d) != "RIFF") return null
            le32(d)
            if (tag(d) != "WAVE") return null
            var format: PcmFormat? = null
            while (true) {
                val id = tag(d)
                val size = le32(d)
                when (id) {
                    "fmt " -> {
                        val audioFormat = le16(d); val channels = le16(d); val rate = le32(d)
                        le32(d); le16(d); val bits = le16(d)
                        if (audioFormat != 1 || bits !in setOf(8, 16) || channels !in 1..2 || rate <= 0) return null
                        format = PcmFormat(rate, channels, bits)
                        skip(d, size - 16L)
                    }
                    "data" -> return format
                    else -> skip(d, size.toLong())
                }
                if (size % 2 == 1) skip(d, 1)
            }
        } catch (e: EOFException) { return null }
    }

    private fun tag(d: DataInputStream): String { val b = ByteArray(4); d.readFully(b); return String(b, Charsets.US_ASCII) }
    private fun le16(d: DataInputStream): Int { val b0 = d.readUnsignedByte(); val b1 = d.readUnsignedByte(); return b0 or (b1 shl 8) }
    private fun le32(d: DataInputStream): Int = le16(d) or (le16(d) shl 16)
    private fun skip(d: DataInputStream, n: Long) { var left = n; while (left > 0) { val s = d.skip(left); if (s <= 0) { d.readByte(); left-- } else left -= s } }
}
