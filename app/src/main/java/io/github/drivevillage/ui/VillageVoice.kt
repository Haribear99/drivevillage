package io.github.drivevillage.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import io.github.drivevillage.core.voice.FocusPort
import io.github.drivevillage.core.voice.PcmFormat
import io.github.drivevillage.core.voice.PcmSink
import io.github.drivevillage.core.voice.PlayOutcome
import io.github.drivevillage.core.voice.VoicePlayback
import io.github.drivevillage.core.voice.WavReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 정차 중에만 쓰는 마을 목소리. 기기의 기본 TTS 엔진·기본 음성(프리셋)만 쓰고 목소리 복제·변조는 없다.
 *
 * 합성과 재생을 나눈다(소리의 fail-closed를 앱이 직접 보장하기 위해).
 * - **합성**: TTS 엔진이 문장을 앱 캐시의 WAV 파일로 만든다. 이 단계는 소리를 내지 않으며, 엔진 호출(IPC)은 전용 스레드에서만 한다.
 *   메인·차량 신호 스레드는 엔진 응답을 기다리지 않는다.
 * - **재생**: 앱이 연 AudioTrack에 작은 조각으로 쓴다. 매 조각 전에 발화 토큰과 허용 조건을 확인한다([VoicePlayback]).
 * - **중단**([stop]): 잠금 없이 세대를 올리고 재생 중인 트랙을 멈춘다. 안전 래치 콜백 스레드에서 바로 불린다.
 * - 포커스는 재생 스레드 하나에서 재생마다 요청·반납한다. 포커스를 잃으면(일시·duck 포함) 멈춘다.
 * - 엔진이 없거나 한국어를 지원하지 않으면 조용히 비활성(화면 문장은 그대로 보인다).
 */
class VillageVoice(context: Context, allowedNow: () -> Boolean) {

    enum class Status { INITIALIZING, READY, UNAVAILABLE }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            ) stop("focus-lost-$change")
        }
        .build()

    private val playback = VoicePlayback(allowedNow)
    private val synthThread: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "voice-synth") }
    private val playThread: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "voice-play") }
    private val pending = ConcurrentHashMap<String, Pair<Long, File>>()
    /** 인스턴스 전용 폴더: 화면을 다시 열어 생긴 새 인스턴스와 합성 파일을 공유하지 않는다. */
    private val root = File(app.cacheDir, "voice")
    private val dir = File(root, UUID.randomUUID().toString())
    /** 합성·재생 대기·포커스 요청·재생 전체를 덮는 '진행 중 발화' 토큰. 0이면 없음. */
    private val activeToken = AtomicLong(0L)
    @Volatile private var track: AudioTrack? = null
    @Volatile private var shutDown = false

    private val _status = MutableStateFlow(Status.INITIALIZING)
    val status: StateFlow<Status> = _status

    // 생성(서비스 바인딩 요청)만 메인에서 하고, 초기화 결과 처리는 엔진 콜백이 반환된 뒤 합성 스레드에서 한다
    // (엔진 내부 잠금을 쥔 채 앱 코드가 재진입하지 않게. 엔진이 없으면 생성자 안에서 바로 실패 콜백이 올 수도 있다).
    private val tts: TextToSpeech = TextToSpeech(app) { result -> main.post { if (!shutDown) submit(synthThread) { onInit(result) } } }

    init {
        submit(synthThread) {
            dir.mkdirs()
            // 오래된(이전 프로세스가 남긴) 세션 폴더만 정리한다. 살아 있는 다른 인스턴스의 폴더는 건드리지 않는다.
            val cutoff = System.currentTimeMillis() - STALE_DIR_MS
            root.listFiles()?.filter { it != dir && it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        }
    }

    /** 종료 뒤의 늦은 콜백이 작업을 제출해도 크래시하지 않는다. 제출되지 않았으면 false. */
    private fun submit(exec: ExecutorService, task: () -> Unit): Boolean =
        try { exec.execute(task); true } catch (e: RejectedExecutionException) { false }

    /** 합성 스레드에서 실행. */
    private fun onInit(result: Int) {
        if (shutDown) return
        if (result != TextToSpeech.SUCCESS) { _status.value = Status.UNAVAILABLE; Log.i(TAG, "engine unavailable ($result)"); return }
        val lang = runCatching { tts.setLanguage(Locale.KOREAN) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            _status.value = Status.UNAVAILABLE; Log.i(TAG, "korean unsupported ($lang)"); return
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit // 합성 시작(소리 없음)
            override fun onDone(utteranceId: String?) { synthesized(utteranceId) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { discard(utteranceId) }
            override fun onError(utteranceId: String?, errorCode: Int) { discard(utteranceId) }
        })
        _status.value = Status.READY
        Log.i(TAG, "ready")
    }

    /** 현재 세대. 호출자가 요청 당시 세대를 넘기면 그 사이의 중단·교체를 감지한다. */
    fun generation(): Long = playback.current()

    /** 메인 스레드에서 부른다. 엔진 호출은 합성 스레드로 넘기고 바로 돌아온다. */
    fun speak(text: String, requestedAt: Long) {
        if (shutDown || _status.value != Status.READY) return
        if (playback.current() != requestedAt) return
        val token = playback.supersede() // 이전 발화(재생 중이면 다음 조각에서) 중단
        if (!playback.isValid(token)) return
        stopTrack()
        activeToken.set(token)
        val id = "line-$token"
        val file = File(dir, "$id.wav")
        pending[id] = token to file
        // 엔진이 완료·오류 콜백 없이 죽는 경우를 대비한 만료: 엔진 응답을 기다리지 않고 기록만 지운다(늦은 완료는 무시됨).
        main.postDelayed({ expire(id) }, SYNTH_TIMEOUT_MS)
        val submitted = submit(synthThread) {
            // 엔진 대기열의 이전 합성(이미 무효)을 버리고 최신 요청만 합성한다.
            runCatching { tts.stop() }
            purgeInvalid()
            if (shutDown || !playback.isValid(token)) { discard(id); return@submit }
            val r = runCatching { tts.synthesizeToFile(text, null, file, id) }.getOrDefault(TextToSpeech.ERROR)
            if (r != TextToSpeech.SUCCESS) discard(id) else Log.i(TAG, "synth $id")
        }
        if (!submitted) discard(id)
    }

    /** 무효가 된 대기 합성의 기록과 파일을 정리한다. */
    private fun purgeInvalid() {
        for ((id, entry) in pending.entries.toList()) {
            if (!playback.isValid(entry.first)) discard(id)
        }
    }

    /** 엔진 콜백 스레드. 합성이 끝난 파일을 재생 스레드에 넘긴다(그 사이 무효가 됐으면 버린다). */
    private fun synthesized(id: String?) {
        val (token, file) = pending.remove(id ?: return) ?: return
        if (shutDown || !playback.isValid(token)) { file.delete(); finish(token); Log.i(TAG, "drop $id"); return }
        if (!submit(playThread) { playFile(id, token, file) }) { file.delete(); finish(token) }
    }

    private fun discard(id: String?) {
        val (token, file) = pending.remove(id ?: return) ?: return
        file.delete()
        finish(token)
    }

    /** 해당 발화만 완료 처리한다(그 사이 시작된 새 발화의 진행 상태는 지우지 않는다). */
    private fun finish(token: Long) { activeToken.compareAndSet(token, 0L) }

    private fun expire(id: String) {
        val (token, file) = pending.remove(id) ?: return
        finish(token)
        Log.w(TAG, "synth timeout $id")
        if (!submit(synthThread) { file.delete() }) file.delete()
    }

    /** 재생 스레드. */
    private fun playFile(id: String, token: Long, file: File) {
        try {
            BufferedInputStream(FileInputStream(file)).use { input ->
                val format = WavReader.readHeader(input) ?: run { Log.w(TAG, "bad wav $id"); return }
                val outcome = playback.play(token, format, input, TrackSink(token), Focus())
                Log.i(TAG, "$outcome $id")
            }
        } catch (e: Exception) {
            Log.w(TAG, "play failed $id", e)
        } finally {
            file.delete()
            finish(token)
        }
    }

    /** 합성~재생 전체 동안 참(그 사이 중단·교체되면 거짓). */
    fun isSpeaking(): Boolean { val t = activeToken.get(); return t != 0L && playback.isValid(t) }

    /**
     * 어느 스레드에서 불러도 된다(래치 콜백 스레드 포함). 잠금이나 엔진 응답을 기다리지 않는다.
     * 세대를 올려 대기·재생 중인 발화를 모두 무효로 만들고, 재생 중인 트랙을 즉시 멈춰 버퍼를 비운다.
     */
    fun stop(reason: String) {
        playback.cancel()
        activeToken.set(0L)
        stopTrack()
        // 엔진 대기열 정리는 합성 스레드에 맡긴다(호출 스레드는 엔진 응답을 기다리지 않는다).
        submit(synthThread) { runCatching { tts.stop() }; purgeInvalid() }
        Log.i(TAG, "stop $reason")
    }

    private fun stopTrack() {
        val t = track ?: return
        runCatching { t.pause(); t.flush() }
    }

    /** 여러 번 불러도 안전하다. 이 인스턴스의 폴더만 지운다. */
    fun shutdown() {
        if (shutDown) return
        stop("shutdown")
        shutDown = true
        submit(synthThread) {
            runCatching { tts.shutdown() }
            pending.keys.toList().forEach { discard(it) }
            dir.deleteRecursively()
        }
        synthThread.shutdown()
        playThread.shutdown()
    }

    /** AudioTrack 출력. 재생 스레드에서만 연다·쓴다·닫는다([stop]만 다른 스레드에서 pause·flush). */
    private inner class TrackSink(private val token: Long) : PcmSink {
        private var t: AudioTrack? = null
        private var frameBytes = 2
        private var written = 0L
        override fun open(format: PcmFormat): Boolean {
            val channel = if (format.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val encoding = if (format.bitsPerSample == 8) AudioFormat.ENCODING_PCM_8BIT else AudioFormat.ENCODING_PCM_16BIT
            val min = AudioTrack.getMinBufferSize(format.sampleRate, channel, encoding)
            if (min <= 0) return false
            val created = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(format.sampleRate).setChannelMask(channel).setEncoding(encoding).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(min) // 버퍼를 작게: 멈출 때 남는 소리를 줄인다
                    .build()
            }.getOrNull() ?: return false
            frameBytes = format.channels.coerceAtLeast(1) * (format.bitsPerSample / 8).coerceAtLeast(1)
            t = created
            track = created
            created.play()
            Log.i(TAG, "start")
            return true
        }
        /**
         * 비차단 쓰기. 버퍼가 차 있으면 잠깐 쉬며 다시 쓰되, 그 사이 발화가 무효가 되면 바로 돌아간다
         * (멈춘 트랙에 차단 쓰기를 하다 재생 스레드가 묶이지 않게).
         */
        override fun write(buf: ByteArray, len: Int): Int {
            val tr = t ?: return -1
            var off = 0
            while (off < len) {
                if (!playback.isValid(token)) return len
                val n = tr.write(buf, off, len - off, AudioTrack.WRITE_NON_BLOCKING)
                if (n < 0) return n
                off += n
                if (off < len) Thread.sleep(5)
            }
            written += len
            return len
        }
        override fun drain(stillValid: () -> Boolean) {
            val tr = t ?: return
            val frames = written / frameBytes
            var waited = 0
            while (stillValid() && (tr.playbackHeadPosition.toLong() and 0xffffffffL) < frames && waited < DRAIN_MAX_MS) {
                Thread.sleep(10); waited += 10
            }
        }
        override fun close() {
            val c = t ?: return
            if (track === c) track = null
            runCatching { c.pause(); c.flush(); c.release() }
            t = null
        }
    }

    private inner class Focus : FocusPort {
        override fun request(): Boolean = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        override fun abandon() { audio.abandonAudioFocusRequest(focusRequest) }
    }

    companion object {
        const val TAG = "DriveVillageVoice"
        private const val DRAIN_MAX_MS = 3_000
        private const val STALE_DIR_MS = 60 * 60 * 1000L
        private const val SYNTH_TIMEOUT_MS = 10_000L
    }
}
