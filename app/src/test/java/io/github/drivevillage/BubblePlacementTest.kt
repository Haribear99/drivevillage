package io.github.drivevillage

import io.github.drivevillage.core.village.Area
import io.github.drivevillage.core.village.BubblePlacement
import io.github.drivevillage.core.village.BubbleSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePlacementTest {

    private val bounds = Area(0f, 100f, 600f, 600f)
    private val speaker = Area(280f, 300f, 320f, 400f) // 머리 300, 발 400
    private val label = Area(275f, 402f, 325f, 430f)

    private fun choose(facilities: List<Area>, others: List<Area> = emptyList(), w: Float = 200f, h: Float = 80f, speakerBox: Area = speaker, labelBox: Area = label) =
        BubblePlacement.choose(w, h, speakerBox, labelBox, bounds, gap = 14f, tailMargin = 23f, facilities = facilities, others = others)

    @Test fun prefersAboveTheHeadWhenNothingIsThere() {
        val p = choose(emptyList())
        assertEquals(BubbleSide.ABOVE, p.side)
        assertEquals(300f - 14f, p.box.b, 0.01f)
        assertEquals("꼬리는 주민 머리 위 가운데를 가리킨다", 300f, p.tail, 0.01f)
    }

    @Test fun movesAwayFromAFacilityAboveTheHead() {
        val windmill = Area(150f, 150f, 450f, 290f) // 머리 위를 통째로 차지
        val p = choose(listOf(windmill))
        assertTrue("시설과 겹치지 않는 쪽을 고른다: ${p.side}", p.box.overlap(windmill) == 0f)
        assertTrue(p.side != BubbleSide.ABOVE)
    }

    @Test fun neverCoversTheSpeakerOrSitsUnderTheTopBar() {
        // 위는 상단 바(bounds 밖)라 공간이 없고, 아래는 공간이 있다.
        val high = Area(280f, 120f, 320f, 220f)
        val highLabel = Area(275f, 222f, 325f, 250f)
        val p = choose(emptyList(), speakerBox = high, labelBox = highLabel)
        assertTrue(p.box.t >= bounds.t)
        assertEquals(0f, p.box.overlap(high), 0f)
        assertEquals(0f, p.box.overlap(highLabel), 0f)
    }

    @Test fun staysInsideThePaneAndTailStillPointsAtTheSpeaker() {
        // 화면 오른쪽 끝의 주민: 말풍선은 안쪽으로 밀리고 꼬리는 주민 쪽 끝으로 붙는다.
        val edgeSpeaker = Area(560f, 300f, 595f, 400f)
        val edgeLabel = Area(555f, 402f, 598f, 430f)
        val p = choose(emptyList(), speakerBox = edgeSpeaker, labelBox = edgeLabel)
        assertTrue(p.box.l >= bounds.l && p.box.r <= bounds.r && p.box.t >= bounds.t && p.box.b <= bounds.b)
        if (p.side == BubbleSide.ABOVE || p.side == BubbleSide.BELOW) {
            assertTrue(p.tail in p.box.l..p.box.r)
            assertTrue(p.tail <= edgeSpeaker.r && p.tail >= p.box.r - 23f - 0.01f)
        }
    }

    @Test fun othersCountLessThanFacilities() {
        // 위에는 다른 주민, 아래에는 시설: 시설을 덜 가리는 위쪽을 고른다.
        val otherAbove = Area(250f, 200f, 350f, 290f)
        val facilityBelow = Area(150f, 440f, 450f, 600f)
        val right = Area(330f, 250f, 600f, 450f)
        val left = Area(0f, 250f, 270f, 450f)
        val p = choose(listOf(facilityBelow, right, left), others = listOf(otherAbove))
        assertEquals(BubbleSide.ABOVE, p.side)
    }
}
