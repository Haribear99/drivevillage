package io.github.drivevillage.core.gate

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 되돌릴 수 없는 안전 래치. 차량 신호 콜백 스레드에서 **동기적으로** 호출된다.
 *
 * StateFlow는 느린 수집자에게 중간 값을 합쳐 버릴 수 있다(P→D→P가 P로 보일 수 있음).
 * 그래서 '마을 화면이 열린 뒤(armed) 들어온 위험 신호'는 콜백 시점에 여기서 바로 기록하고,
 * 한 번 걸리면 그 세션에서는 다시 풀리지 않는다. 화면 종료와 차량 쓰기 허용 여부는 이 래치를 따른다.
 *
 * 차량 쓰기는 [withLock] 안에서 '검사 → 전송'을 한 번에 한다. 래치를 거는 쪽은 그 잠금을
 * 최대 [TRIP_WAIT_MS]만 기다리고, 넘으면 잠금 없이 건다(메인 스레드가 SDK 호출 지연에 묶이지 않게).
 * 이때 진행 중인 전송은 이미 검사를 통과한 것이다.
 */
class SafetyLatch {
    private val lock = ReentrantLock()
    @Volatile private var armed = false
    @Volatile private var lastGear = GearState.UNKNOWN
    @Volatile private var lastUx = UxState.UNKNOWN
    @Volatile var reason: String? = null
        private set

    private val _tripped = MutableStateFlow(false)
    /** 한 번 true가 되면 다시 false가 되지 않는다(합쳐져도 안전). */
    val tripped: StateFlow<Boolean> = _tripped

    val isTripped: Boolean get() = _tripped.value

    /**
     * 래치가 걸린 직후, 건 스레드(차량 콜백 스레드일 수 있음)에서 **동기적으로** 불린다. 소리처럼 메인 스레드 수집자를
     * 기다리면 안 되는 출력의 즉시 중단에 쓴다. 래치 잠금을 놓은 뒤 부르며, 여러 번 불려도 안전해야 한다.
     */
    @Volatile var onTrip: (() -> Unit)? = null

    fun onGear(value: GearState) = guarded {
        lastGear = value
        if (value != GearState.PARK && armed) setTripped("gear-$value")
    }

    fun onUx(value: UxState) = guarded {
        lastUx = value
        when (value) {
            UxState.RESTRICTED -> setTripped("ux-restricted") // 제한은 열리기 전이라도 즉시 종료
            UxState.UNKNOWN -> if (armed) setTripped("ux-unknown")
            UxState.UNRESTRICTED -> Unit
        }
    }

    /** 마을 화면이 열린 순간. 이 시점의 최신 값이 안전하지 않으면 바로 건다. */
    fun arm() = guarded {
        armed = true
        if (lastGear != GearState.PARK) setTripped("arm-gear-$lastGear")
        if (lastUx != UxState.UNRESTRICTED) setTripped("arm-ux-$lastUx")
    }

    fun trip(why: String) = guarded { setTripped(why) }

    /** 차량 쓰기의 '검사 → 전송' 구간. 이 안에서는 다른 스레드가 래치 상태를 바꾸지 못한다(최대 대기 시간 내). */
    fun <T> withLock(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }

    /** 차량 쓰기 직전(SDK 스레드)에서 확인. 열린 화면 + 래치 미작동 + 최신 값 P·제한 없음. */
    fun allowsWrite(): Boolean =
        armed && !_tripped.value && lastGear == GearState.PARK && lastUx == UxState.UNRESTRICTED

    private fun setTripped(why: String) {
        if (_tripped.value) return
        reason = why
        _tripped.value = true
    }

    private inline fun guarded(block: () -> Unit) {
        val before = _tripped.value
        val got = try { lock.tryLock(TRIP_WAIT_MS, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { false }
        try { block() } finally { if (got) lock.unlock() }
        if (!before && _tripped.value) runCatching { onTrip?.invoke() }
    }

    private companion object { const val TRIP_WAIT_MS = 50L }
}
