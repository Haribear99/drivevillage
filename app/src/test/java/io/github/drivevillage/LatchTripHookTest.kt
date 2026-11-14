package io.github.drivevillage

import io.github.drivevillage.core.gate.GearState
import io.github.drivevillage.core.gate.SafetyLatch
import io.github.drivevillage.core.gate.UxState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** 소리 즉시 중단용 onTrip 훅: 래치를 건 스레드에서 동기적으로, 래치가 걸린 뒤에 불린다. */
class LatchTripHookTest {

    private fun armedLatch() = SafetyLatch().apply {
        onGear(GearState.PARK); onUx(UxState.UNRESTRICTED); arm()
    }

    @Test fun hookRunsSynchronouslyOnCallerThreadAfterTrip() {
        val latch = armedLatch()
        var seenTripped = false
        var hookThread: Thread? = null
        latch.onTrip = { seenTripped = latch.isTripped; hookThread = Thread.currentThread() }
        val t = Thread { latch.onGear(GearState.NOT_PARK) }
        t.start(); t.join()
        assertTrue("훅이 불릴 때 래치는 이미 걸려 있어야 한다", seenTripped)
        assertEquals("래치를 건 스레드에서 동기 호출", t, hookThread)
    }

    @Test fun hookFiresOncePerTransitionNotOnRepeatedDangerSignals() {
        val latch = armedLatch()
        val calls = AtomicInteger()
        latch.onTrip = { calls.incrementAndGet() }
        latch.onGear(GearState.NOT_PARK)
        latch.onUx(UxState.RESTRICTED)
        latch.trip("closed")
        assertEquals(1, calls.get())
    }

    @Test fun hookFailureDoesNotBreakLatch() {
        val latch = armedLatch()
        latch.onTrip = { error("boom") }
        latch.onGear(GearState.NOT_PARK)
        assertTrue(latch.isTripped)
    }
}

