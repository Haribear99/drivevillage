package io.github.drivevillage.core.voice

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 효과음 출력(실기기에서는 앱 공용 포커스 + SoundPool). [L]은 포커스 임대 표. */
interface SfxPort<L : Any> {
    /** 포커스 임대. 받지 못하면 null(소리를 내지 않는다). */
    fun acquire(): L?
    fun release(lease: L)
    /** 재생을 시작하고 스트림 번호를 돌려준다. 실패하면 0. */
    fun play(sample: Int): Int
    fun stop(stream: Int)
}

enum class SfxStart { DROPPED, FOCUS_DENIED, FAILED, RACED, PLAYING }

/**
 * 효과음 재생의 안전 규칙(순수 로직). Android 쪽([io.github.drivevillage.ui.VillageSfx])은 스레드·SoundPool 연결만 맡는다.
 *
 * - 요청할 때 받은 세대 토큰으로 [start]한다. [stopAll]은 세대를 올려 그 전 토큰을 모두 무효로 만든다(잠금 없음, 어느 스레드든).
 * - [start]는 포커스 받기 전, 포커스 받은 뒤(play 직전), play 직후 세 번 [isValid]를 확인한다.
 *   play 직전에 무효가 되면 play하지 않고 임대를 반납한다. play 직후에 무효가 되면(stopAll이 아직 등록 전이라 이 스트림을 못 본 경우)
 *   방금 낸 스트림을 바로 멈추고 반납한다. 등록 뒤의 [stopAll]은 등록된 스트림을 직접 멈춘다.
 * - 임대 반납은 재생 하나당 한 번만([finish]).
 */
class SfxPlayback<L : Any>(private val port: SfxPort<L>, private val allowedNow: () -> Boolean) {

    class Play<L : Any> internal constructor(val stream: Int, internal val lease: L) {
        internal val done = AtomicBoolean(false)
    }

    private val generation = AtomicLong()
    private val active = ConcurrentHashMap<Int, Play<L>>()
    @Volatile var shutDown = false
        private set

    /** 지금 세대. 요청할 때 받아 [start]에 넘긴다. */
    fun token(): Long = generation.get()

    fun isValid(token: Long): Boolean = !shutDown && generation.get() == token && allowedNow()

    /** 재생 스레드에서 부른다. [SfxStart.PLAYING]이면 두 번째 값(재생)을 소리 길이 뒤 [finish]해야 한다. */
    fun start(sample: Int, token: Long): Pair<SfxStart, Play<L>?> {
        if (!isValid(token)) return SfxStart.DROPPED to null
        val lease = port.acquire() ?: return SfxStart.FOCUS_DENIED to null
        if (!isValid(token)) { port.release(lease); return SfxStart.DROPPED to null }
        val stream = port.play(sample)
        if (stream == 0) { port.release(lease); return SfxStart.FAILED to null }
        val p = Play(stream, lease)
        active[stream] = p
        // stopAll과 겹쳤으면(세대가 바뀜) 방금 낸 소리를 바로 멈춘다.
        if (!isValid(token)) { runCatching { port.stop(stream) }; finish(p); return SfxStart.RACED to null }
        return SfxStart.PLAYING to p
    }

    /** 재생 하나를 끝낸다(임대 반납). 여러 번 불러도 한 번만 반납한다. */
    fun finish(p: Play<L>) {
        if (!p.done.compareAndSet(false, true)) return
        active.remove(p.stream)
        port.release(p.lease)
    }

    /** 어느 스레드에서 불러도 된다. 세대를 올리고 등록된 스트림을 모두 멈춘다. 반납할 재생 목록을 돌려준다(반납은 호출자가 [finish]). */
    fun stopAll(): List<Play<L>> {
        generation.incrementAndGet()
        val playing = active.values.toList()
        for (p in playing) runCatching { port.stop(p.stream) }
        return playing
    }

    /** 이후 모든 요청을 거부한다. */
    fun markShutdown() { shutDown = true }

    fun activeCount(): Int = active.size
}
