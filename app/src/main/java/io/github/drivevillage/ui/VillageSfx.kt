package io.github.drivevillage.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.drivevillage.R
import io.github.drivevillage.core.voice.SharedFocus
import io.github.drivevillage.core.voice.Sfx
import io.github.drivevillage.core.voice.SfxPlayback
import io.github.drivevillage.core.voice.SfxPort
import io.github.drivevillage.core.voice.SfxRules
import io.github.drivevillage.core.voice.SfxStart
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 화면 쪽에서 효과음을 낼 때 쓰는 통로. 기본값은 아무것도 하지 않는다(미리보기·다른 화면). */
val LocalSfx = staticCompositionLocalOf<(Sfx) -> Unit> { {} }

/**
 * 짧은 효과음(직접 합성한 res/raw/sfx_*.wav, tools/gen_sfx.py). SoundPool로 미리 올려 두고 낸다.
 *
 * - 낼 때마다 [allowedNow](화면·래치·소리 켬·전면)를 메인 스레드와 재생 스레드에서 두 번 확인하고, 앱 공용 포커스를 받는다.
 *   앱이 아직 포커스를 갖지 않았고 다른 앱의 미디어가 재생 중이면 효과음은 내지 않는다(사용자 음악을 효과음 때문에 끊지 않게).
 * - [stopAll]은 어느 스레드에서 불러도 된다(안전 래치 콜백 스레드 포함). 세대를 올리고 재생 중인 스트림을 바로 stop한다.
 *   재생 스레드가 막 play를 부른 순간과 겹치면, play 뒤의 재확인에서 곧바로 멈춘다.
 * - [shutdown] 뒤에는 SoundPool을 해제하고 어떤 요청도 받지 않는다.
 */
class VillageSfx(context: Context, private val focus: AppAudioFocus, private val allowedNow: () -> Boolean) {

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(AppAudioFocus.attributes(AudioAttributes.CONTENT_TYPE_SONIFICATION))
        .build()
    private val loaded = ConcurrentHashMap.newKeySet<Int>()
    private val ids: Map<Sfx, IntArray>
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sfx") }
    private val rules = SfxRules()
    /** 세대·재확인·반납 규칙(순수 로직, 단위 테스트). 여기서는 포커스와 SoundPool만 이어 준다. */
    private val playback = SfxPlayback(
        object : SfxPort<SharedFocus.Lease> {
            override fun acquire(): SharedFocus.Lease? = focus.shared.acquire { !focus.otherMediaActive() }
            override fun release(lease: SharedFocus.Lease) = focus.shared.release(lease)
            override fun play(sample: Int): Int = pool.play(sample, VOLUME, VOLUME, 1, 0, 1f)
            override fun stop(stream: Int) = pool.stop(stream)
        },
        allowedNow,
    )

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) loaded += sampleId }
        val app = context.applicationContext
        ids = Sfx.entries.associateWith { s -> IntArray(s.variants) { v -> pool.load(app, resOf(s, v), 1) } }
    }

    /** 메인 스레드에서 부른다. 조건이 안 맞거나 연타면 조용히 버린다. */
    fun play(s: Sfx) {
        if (playback.shutDown || !allowedNow()) return
        val variant = rules.admit(s, SystemClock.uptimeMillis()) ?: return
        val sample = ids.getValue(s)[variant]
        if (sample !in loaded) return
        val token = playback.token()
        submit { start(s, sample, token) }
    }

    /** 재생 스레드. */
    private fun start(s: Sfx, sample: Int, token: Long) {
        val (outcome, p) = playback.start(sample, token)
        when (outcome) {
            SfxStart.FOCUS_DENIED -> Log.i(TAG, "skip $s (focus denied or other media)")
            SfxStart.PLAYING -> if (p != null) {
                Log.i(TAG, "play $s stream=${p.stream}")
                exec.schedule({ playback.finish(p) }, s.durationMs + TAIL_MS, TimeUnit.MILLISECONDS)
            }
            else -> Unit
        }
    }

    /** 어느 스레드에서 불러도 된다. 잠금·포커스 IPC를 기다리지 않는다(포커스 반납은 재생 스레드에서). */
    fun stopAll(reason: String) {
        val playing = playback.stopAll()
        submit { playing.forEach { playback.finish(it) } }
        if (playing.isNotEmpty()) Log.i(TAG, "stopAll $reason streams=${playing.size}")
    }

    /** 여러 번 불러도 안전하다. */
    fun shutdown() {
        if (playback.shutDown) return
        stopAll("shutdown")
        playback.markShutdown()
        submit { pool.release(); Log.i(TAG, "released") }
        exec.shutdown()
    }

    private fun submit(task: () -> Unit) {
        try { exec.execute(task) } catch (e: RejectedExecutionException) { }
    }

    companion object {
        const val TAG = "DriveVillageSfx"
        private const val MAX_STREAMS = 3
        /** 기본 재생 볼륨(파일 자체가 -12dBFS). 미디어 볼륨에 다시 곱해진다. */
        private const val VOLUME = 0.6f
        /** 파일 길이 뒤 포커스를 놓기까지 여유(출력 버퍼에 남은 소리). */
        private const val TAIL_MS = 120L

        fun resOf(s: Sfx, variant: Int): Int = when (s) {
            Sfx.TAP -> intArrayOf(R.raw.sfx_tap_1, R.raw.sfx_tap_2, R.raw.sfx_tap_3)
            Sfx.SELECT -> intArrayOf(R.raw.sfx_select_1, R.raw.sfx_select_2)
            Sfx.CANCEL -> intArrayOf(R.raw.sfx_cancel_1, R.raw.sfx_cancel_2)
            Sfx.PLACE -> intArrayOf(R.raw.sfx_place_1, R.raw.sfx_place_2)
            Sfx.LIGHT -> intArrayOf(R.raw.sfx_light_1)
            Sfx.CHIME -> intArrayOf(R.raw.sfx_chime_1)
        }[variant]
    }
}
