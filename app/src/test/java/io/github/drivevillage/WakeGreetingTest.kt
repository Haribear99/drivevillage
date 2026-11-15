package io.github.drivevillage

import io.github.drivevillage.ui.WakeGreeting
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** '했어요' 점등 인사 예약: 연출 시간 안에 두 시설이 켜지면 마지막 점등의 인사만, 그 연출이 끝난 뒤에 뜬다. */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeGreetingTest {
    private val delayMs = 1_300L

    @Test fun twoWakesWithinDelay_onlySecondGreets_afterItsOwnAnimation() = runTest {
        val shown = mutableListOf<String>()
        val g = WakeGreeting(this, delayMs)
        val a = Any()
        val b = Any()
        g.lit(a, reduceMotion = false, canShow = { true }) { shown += "A" }
        advanceTimeBy(500); runCurrent()
        g.lit(b, reduceMotion = false, canShow = { true }) { shown += "B" }
        // 첫 점등 기준 1.3초: 두 번째 연출 중이라 아무것도 뜨지 않는다(예전에는 여기서 A가 떴다).
        advanceTimeBy(800); runCurrent()
        assertEquals(emptyList<String>(), shown)
        // 두 번째 점등 기준 1.3초가 지나면 B만.
        advanceTimeBy(500); runCurrent()
        assertEquals(listOf("B"), shown)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf("B"), shown)
    }

    @Test fun singleWake_greetsAfterDelay_onlyWhenStillAllowed() = runTest {
        val shown = mutableListOf<String>()
        val g = WakeGreeting(this, delayMs)
        g.lit(Any(), reduceMotion = false, canShow = { true }) { shown += "A" }
        advanceTimeBy(delayMs - 1); runCurrent()
        assertEquals(emptyList<String>(), shown)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("A"), shown)
        // 그 사이 다른 말풍선이 떴거나 화면이 닫혔으면 띄우지 않는다.
        var allowed = true
        g.lit(Any(), reduceMotion = false, canShow = { allowed }) { shown += "B" }
        allowed = false
        advanceTimeBy(delayMs); runCurrent()
        assertEquals(listOf("A"), shown)
    }

    @Test fun reduceMotion_greetsImmediately_andCancelsEarlierPending() = runTest {
        val shown = mutableListOf<String>()
        val g = WakeGreeting(this, delayMs)
        g.lit(Any(), reduceMotion = false, canShow = { true }) { shown += "A" }
        advanceTimeBy(300); runCurrent()
        g.lit(Any(), reduceMotion = true, canShow = { true }) { shown += "B" }
        assertEquals(listOf("B"), shown)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf("B"), shown) // 앞 예약(A)은 취소됐다
    }
}
