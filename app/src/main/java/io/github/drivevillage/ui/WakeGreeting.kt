package io.github.drivevillage.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * '했어요' 점등 뒤 주민 인사 예약(Android에 기대지 않는 순수 코루틴 로직, 메인 스레드에서만 부른다).
 *
 * 점등 연출(약 1.3초) 동안 말풍선을 비워 두었다가 끝나면 인사를 띄운다. 두 시설이 연출 시간 안에 연달아 켜지면
 * 앞 점등의 인사 예약을 취소하고, 지연이 끝난 순간 자기 점등이 여전히 마지막 점등일 때만 띄운다
 * (앞 인사가 두 번째 연출 중에 뜨고 두 번째 인사가 버려지던 경쟁을 막는다).
 */
internal class WakeGreeting(private val scope: CoroutineScope, private val delayMs: Long) {
    private var pending: Job? = null
    private var latest: Any? = null

    /**
     * 새 점등 [event]. [reduceMotion]이면 연출이 없으니 바로 [show]. 아니면 [delayMs] 뒤에
     * 그 사이 다른 점등이 없었고 [canShow](다른 말풍선 없음·마을 화면)가 참일 때만 [show].
     */
    fun lit(event: Any, reduceMotion: Boolean, canShow: () -> Boolean, show: () -> Unit) {
        pending?.cancel()
        pending = null
        latest = event
        if (reduceMotion) { show(); return }
        pending = scope.launch {
            delay(delayMs)
            if (latest === event && canShow()) show()
        }
    }
}
