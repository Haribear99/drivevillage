package io.github.drivevillage.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import io.github.drivevillage.core.voice.FocusApi
import io.github.drivevillage.core.voice.SharedFocus

/**
 * 앱 소리 전체(주민 음성 + 효과음)의 오디오 속성과 포커스 하나.
 *
 * - usage는 USAGE_GAME. 앱을 게임(appCategory="game")으로 선언했고, Pleos 오디오 포커스 가이드의 usage→컨텍스트 표에서
 *   ASSISTANT는 음성 비서(Gleo)와 같은 VOICE_COMMAND로, GAME은 MUSIC으로 묶인다(연구 노트 05 1절).
 *   https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development
 * - 포커스는 Pleos 가이드대로 모든 소리가 재생 전에 요청하고, 짧은 재생이라 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK로 요청한다.
 *   잃으면(영구·일시·duck 모두) [onLost]로 모든 소리를 즉시 멈추고 반납한다. duck 되면 일시정지하겠다고 알린다.
 */
class AppAudioFocus(context: Context, private val onLost: (Int) -> Unit) {

    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes(AudioAttributes.CONTENT_TYPE_SONIFICATION))
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            ) {
                runCatching { onLost(change) }
                shared.onLost()
                Log.i(TAG, "focus lost $change")
            }
        }
        .build()

    val shared: SharedFocus = SharedFocus(object : FocusApi {
        override fun request(): Boolean {
            val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.i(TAG, "focus request granted=$granted")
            return granted
        }
        override fun abandon() {
            audio.abandonAudioFocusRequest(request)
            Log.i(TAG, "focus abandon")
        }
    })

    /** 다른 앱의 미디어(MUSIC 계열)가 지금 재생 중인가. 앱이 포커스를 갖지 않은 때에만 물어 자기 소리와 섞지 않는다. */
    fun otherMediaActive(): Boolean = runCatching { audio.isMusicActive }.getOrDefault(false)

    companion object {
        const val TAG = "DriveVillageAudio"

        fun attributes(contentType: Int): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(contentType)
            .build()
    }
}
