package io.github.drivevillage.core.voice

/** 실제 오디오 포커스 요청·반납(실기기에서는 AudioManager). */
interface FocusApi {
    fun request(): Boolean
    fun abandon()
}

/**
 * 앱 전체(주민 음성 + 효과음)가 함께 쓰는 오디오 포커스 하나(순수 로직).
 *
 * - 같은 앱의 두 소리가 각자 포커스를 요청하면 서로의 요청이 상대에게 '포커스 잃음'으로 보여 서로를 끊을 수 있다.
 *   그래서 요청은 하나로 묶고, 쓰는 쪽은 [acquire]로 받은 [Lease]를 끝날 때 [release]한다. 마지막 보유자가 놓으면 반납한다.
 * - 포커스를 잃으면([onLost]) 세대를 올려 기존 임대를 모두 무효로 만들고 바로 반납한다. 늦게 도착한 옛 임대의
 *   [release]는 무시되어, 그 사이 새로 받은 임대의 포커스를 일찍 반납하지 않는다.
 * - [acquire]의 allowFresh는 '새로 요청해야 할 때만' 확인한다(이미 앱이 포커스를 가진 동안에는 묻지 않는다).
 */
class SharedFocus(private val api: FocusApi) {

    class Lease internal constructor(internal val epoch: Long)

    private var epoch = 0L
    private var holders = 0
    private var held = false

    @Synchronized
    fun acquire(allowFresh: () -> Boolean = { true }): Lease? {
        if (!held) {
            if (!allowFresh()) return null
            if (!api.request()) return null
            held = true
        }
        holders++
        return Lease(epoch)
    }

    @Synchronized
    fun release(lease: Lease) {
        if (lease.epoch != epoch || holders == 0) return
        holders--
        if (holders == 0 && held) { held = false; api.abandon() }
    }

    /** 포커스를 잃었다(일시·duck 포함). 보유자들은 소리를 이미 멈췄다고 보고 반납한다. */
    @Synchronized
    fun onLost() {
        epoch++
        holders = 0
        if (held) { held = false; runCatching { api.abandon() } }
    }

    @Synchronized
    fun isHeld(): Boolean = held
}

/**
 * 효과음 종류. [durationMs]는 tools/gen_sfx.py가 만드는 파일 길이(포커스 반납 시점에 쓴다), [variants]는 피치 변형 수,
 * [minGapMs]는 같은 소리를 이보다 촘촘히 내지 않는 간격(연타 피로 방지, 노트 05의 경험칙 기반 설계값).
 */
enum class Sfx(val fileBase: String, val variants: Int, val durationMs: Long, val minGapMs: Long) {
    TAP("tap", 3, 100, 80),
    SELECT("select", 2, 160, 80),
    CANCEL("cancel", 2, 160, 80),
    PLACE("place", 2, 200, 150),
    LIGHT("light", 1, 700, 400),
    CHIME("chime", 1, 800, 1_200),
}

/** 효과음 연타 억제와 변형 순환(순수 로직, 한 스레드에서만 부른다). 소리를 낼지 여부(화면·래치·소리 끔)는 [VoiceGate]가 정한다. */
class SfxRules {
    private val lastAt = LongArray(Sfx.entries.size) { Long.MIN_VALUE }
    private val next = IntArray(Sfx.entries.size)

    /** 낼 수 있으면 쓸 변형 번호(0부터), 너무 촘촘하면 null. 같은 변형이 연달아 나오지 않게 돌려 쓴다. */
    fun admit(s: Sfx, nowMs: Long): Int? {
        val i = s.ordinal
        if (lastAt[i] != Long.MIN_VALUE && nowMs - lastAt[i] < s.minGapMs) return null
        lastAt[i] = nowMs
        val v = next[i]
        next[i] = (v + 1) % s.variants
        return v
    }
}
