package io.github.drivevillage.core.village

/** 화면 좌표의 상자(픽셀). */
data class Area(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w: Float get() = r - l
    val h: Float get() = b - t
    val cx: Float get() = (l + r) / 2
    val cy: Float get() = (t + b) / 2

    fun overlap(o: Area): Float {
        val x = minOf(r, o.r) - maxOf(l, o.l)
        val y = minOf(b, o.b) - maxOf(t, o.t)
        return if (x > 0f && y > 0f) x * y else 0f
    }

    fun shift(dx: Float, dy: Float) = Area(l + dx, t + dy, r + dx, b + dy)
}

/** 말풍선이 말하는 주민의 어느 쪽에 붙는가(꼬리는 반대쪽 변에서 주민을 가리킨다). */
enum class BubbleSide { ABOVE, BELOW, RIGHT, LEFT }

/** [tail]은 꼬리가 붙는 변 위의 위치(ABOVE·BELOW는 x, LEFT·RIGHT는 y). */
data class BubblePlace(val side: BubbleSide, val box: Area, val tail: Float)

/**
 * 주민 말풍선 자리 고르기(순수 계산). 말풍선이 시설(풍차·물레방아·공방과 굴뚝 연기 등)을 덮어 연출이 안 보이던 문제를
 * 줄이려고, 말하는 주민의 위·아래·오른쪽·왼쪽 네 후보 중 시설을 가장 적게 덮는 곳에 둔다.
 *
 * 점수(작을수록 좋음) = 시설과 겹친 넓이 + 다른 주민·이름표와 겹친 넓이 × 0.5 + 말하는 주민·이름표와 겹친 넓이 × 3.
 * 화면 밖으로 나가는 후보는 [bounds] 안으로 밀어 넣은 뒤 계산하고, 밀린 탓에 꼬리가 주민을 가리킬 수 없으면 크게 깎는다.
 * 점수가 같으면 위 → 아래 → 오른쪽 → 왼쪽 순서.
 */
object BubblePlacement {

    fun choose(
        w: Float, h: Float,
        speaker: Area, label: Area,
        bounds: Area, gap: Float, tailMargin: Float,
        facilities: List<Area>, others: List<Area>,
    ): BubblePlace {
        val body = Area(minOf(speaker.l, label.l), speaker.t, maxOf(speaker.r, label.r), maxOf(speaker.b, label.b))
        val candidates = BubbleSide.entries.map { side ->
            val ideal = when (side) {
                BubbleSide.ABOVE -> Area(speaker.cx - w / 2, speaker.t - gap - h, speaker.cx + w / 2, speaker.t - gap)
                BubbleSide.BELOW -> Area(speaker.cx - w / 2, body.b + gap, speaker.cx + w / 2, body.b + gap + h)
                BubbleSide.RIGHT -> Area(body.r + gap, speaker.cy - h / 2, body.r + gap + w, speaker.cy + h / 2)
                BubbleSide.LEFT -> Area(body.l - gap - w, speaker.cy - h / 2, body.l - gap, speaker.cy + h / 2)
            }
            val box = clamp(ideal, bounds)
            // 꼬리는 상자 모서리 둥글기를 피해 [tailMargin] 안쪽에서 주민 쪽을 가리킨다.
            val (target, lo, hi) = when (side) {
                BubbleSide.ABOVE, BubbleSide.BELOW -> Triple(speaker.cx, box.l + tailMargin, box.r - tailMargin)
                BubbleSide.LEFT, BubbleSide.RIGHT -> Triple(speaker.cy, box.t + tailMargin, box.b - tailMargin)
            }
            val tail = if (lo <= hi) target.coerceIn(lo, hi) else (lo + hi) / 2
            var score = facilities.sumOf { box.overlap(it).toDouble() } +
                others.sumOf { box.overlap(it).toDouble() } * 0.5 +
                (box.overlap(speaker) + box.overlap(label)) * 3.0
            // 꼬리가 상자 범위 밖의 주민을 가리켜야 하거나, 밀려서 상자 가운데가 주민의 반대편으로 넘어가면 쓰지 않는다
            // (조금 밀려 이름표에 살짝 걸치는 정도는 위의 겹침 점수로만 깎는다).
            if (target < lo || target > hi) score += 1e9
            val detached = when (side) {
                BubbleSide.ABOVE -> box.cy > speaker.t
                BubbleSide.BELOW -> box.cy < body.b
                BubbleSide.RIGHT -> box.cx < body.r
                BubbleSide.LEFT -> box.cx > body.l
            }
            if (detached) score += 1e8
            Triple(side, BubblePlace(side, box, tail), score)
        }
        return candidates.minBy { it.third }.second
    }

    private fun clamp(b: Area, bounds: Area): Area {
        val dx = when {
            b.w >= bounds.w -> bounds.l - b.l
            b.l < bounds.l -> bounds.l - b.l
            b.r > bounds.r -> bounds.r - b.r
            else -> 0f
        }
        val dy = when {
            b.h >= bounds.h -> bounds.t - b.t
            b.t < bounds.t -> bounds.t - b.t
            b.b > bounds.b -> bounds.b - b.b
            else -> 0f
        }
        return b.shift(dx, dy)
    }
}
