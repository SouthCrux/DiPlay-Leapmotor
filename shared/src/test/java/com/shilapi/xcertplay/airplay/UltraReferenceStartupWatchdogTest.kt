package com.shilapi.xcertplay.airplay

import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

class UltraReferenceStartupWatchdogTest {
    private class Timer {
        data class Task(val delay: Long, val action: () -> Unit, var canceled: Boolean = false)
        var now = 0L
        val tasks = mutableListOf<Task>()
        fun schedule(delay: Long, action: () -> Unit): Closeable {
            val task = Task(delay, action)
            tasks += task
            return Closeable { task.canceled = true }
        }
    }

    @Test fun waitingWithoutAResumedHostAndSurfaceDoesNotCreateATimer() {
        val timer = Timer()
        val watchdog = UltraReferenceStartupWatchdog(clock = { timer.now }, schedule = timer::schedule) { fail("No visible deadline") }
        watchdog.arm()
        watchdog.updateHostVisibility(true, false)
        watchdog.updateHostVisibility(false, true)
        assertTrue(timer.tasks.isEmpty())
        watchdog.updateHostVisibility(true, true)
        assertEquals(10_000L, timer.tasks.single().delay)
        watchdog.close()
    }

    @Test fun backgroundTimeDoesNotConsumeTheRemainingForegroundBudget() {
        val timer = Timer()
        var failures = 0
        val watchdog = UltraReferenceStartupWatchdog(clock = { timer.now }, schedule = timer::schedule) { failures++ }
        watchdog.updateHostVisibility(true, true)
        watchdog.arm()
        timer.now = 3_000
        watchdog.updateHostVisibility(false, true)
        val old = timer.tasks.single()
        assertTrue(old.canceled)
        timer.now += 5 * 60 * 60 * 1000
        old.action() // Simulate a callback already dispatched despite cancellation.
        assertEquals(0, failures)
        watchdog.updateHostVisibility(true, true)
        val resumed = timer.tasks.last()
        assertEquals(7_000L, resumed.delay)
        timer.now += 7_000
        resumed.action()
        resumed.action()
        assertEquals(1, failures)
        watchdog.arm()
        watchdog.updateHostVisibility(false, false)
        watchdog.updateHostVisibility(true, true)
        assertEquals(2, timer.tasks.size)
    }

    @Test fun replacingASurfaceInvalidatesTheOldDeadlineWithoutResettingTheBudget() {
        val timer = Timer()
        var failures = 0
        val watchdog = UltraReferenceStartupWatchdog(clock = { timer.now }, schedule = timer::schedule) { failures++ }
        watchdog.arm()
        watchdog.updateHostVisibility(true, true)
        val first = timer.tasks.single()
        timer.now = 4_000
        watchdog.updateHostVisibility(false, false)
        watchdog.updateHostVisibility(true, true)
        val replacement = timer.tasks.last()
        assertEquals(6_000L, replacement.delay)
        first.action()
        assertEquals(0, failures)
        timer.now = 10_000
        replacement.action()
        assertEquals(1, failures)
    }

    @Test fun openedOrClosedWatchdogsNeverRearmAndIgnoreDispatchedCallbacks() {
        for (opened in listOf(false, true)) {
            val timer = Timer()
            val watchdog = UltraReferenceStartupWatchdog(clock = { timer.now }, schedule = timer::schedule) { fail("Terminal watchdog fired") }
            watchdog.arm()
            watchdog.updateHostVisibility(true, true)
            val task = timer.tasks.single()
            if (opened) watchdog.channelOpened() else watchdog.close()
            assertTrue(task.canceled)
            timer.now = 10_000
            task.action()
            watchdog.arm()
            watchdog.updateHostVisibility(false, false)
            watchdog.updateHostVisibility(true, true)
            assertEquals(1, timer.tasks.size)
        }
        val timer = Timer()
        val preopened = UltraReferenceStartupWatchdog(clock = { timer.now }, schedule = timer::schedule) { fail("Preopened watchdog fired") }
        preopened.channelOpened()
        preopened.updateHostVisibility(true, true)
        preopened.arm()
        assertTrue(timer.tasks.isEmpty())
    }

    @Test fun anEarlyCallbackSchedulesOnlyTheUnspentBudget() {
        val timer = Timer()
        var failures = 0
        val watchdog = UltraReferenceStartupWatchdog(clock = { timer.now }, schedule = timer::schedule) { failures++ }
        watchdog.arm()
        watchdog.updateHostVisibility(true, true)
        timer.now = 8_000
        timer.tasks.single().action()
        assertEquals(2_000L, timer.tasks.last().delay)
        assertEquals(0, failures)
        timer.now = 10_000
        timer.tasks.last().action()
        assertEquals(1, failures)
    }

    @Test fun synchronousDeadlineBeforeHandleRegistrationCannotRetainATaskOrHoldTheCallbackLock() {
        var now = 0L
        var canceled = false
        var failures = 0
        lateinit var watchdog: UltraReferenceStartupWatchdog
        watchdog = UltraReferenceStartupWatchdog(clock = { now }, schedule = { delay, action ->
            now += delay
            action()
            Closeable { canceled = true }
        }) {
            failures++
            val other = Thread { watchdog.close() }
            other.start()
            other.join(1_000)
            assertFalse("Timeout callback held the watchdog lock", other.isAlive)
        }
        watchdog.arm()
        watchdog.updateHostVisibility(true, true)
        assertEquals(1, failures)
        assertTrue(canceled)
    }
    @Test fun deferredExpiredBudgetRetriesAtZeroOnlyWhenVisibleAndCannotReviveAfterCompletion() {
        val timer = Timer()
        var timeouts = 0
        val watchdog = UltraReferenceStartupWatchdog(clock = { timer.now }, schedule = timer::schedule) { timeouts++ }
        watchdog.arm()
        watchdog.updateHostVisibility(true, true)
        timer.now = 10_000
        timer.tasks.last().action()
        assertEquals(1, timeouts)
        watchdog.deferExpiredUntilVisible()
        timer.now = 500_000
        watchdog.updateHostVisibility(false, true)
        assertEquals(1, timer.tasks.size)
        watchdog.updateHostVisibility(true, true)
        assertEquals(0L, timer.tasks.last().delay)
        timer.tasks.last().action()
        assertEquals(2, timeouts)
        watchdog.channelOpened()
        watchdog.deferExpiredUntilVisible()
        watchdog.updateHostVisibility(false, false)
        watchdog.updateHostVisibility(true, true)
        assertEquals(2, timer.tasks.size)
    }

}
