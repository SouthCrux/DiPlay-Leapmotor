package com.shilapi.xcertplay

import android.os.Looper
import com.shilapi.xcertplay.network.CarHotspotTethering.Result
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class ForegroundHotspotRecoveryTest {
    private fun advance(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    @Test fun recoversOnlyAConfirmedOffHotspotWhileVisible() {
        var state: Boolean? = true
        var calls = 0
        val recovery = ForegroundHotspotRecovery({ true }, { state }, { calls++; Result.READY }, { it() })
        recovery.start()
        advance(5)
        state = null
        advance(5)
        assertEquals(0, calls)
        state = false
        advance(5)
        assertEquals(1, calls)
        recovery.stop()
        advance(20)
        assertEquals(1, calls)
    }

    @Test fun optingOutPreventsRequestsAndReturningToAppRestartsRecovery() {
        var eligible = false
        var calls = 0
        val recovery = ForegroundHotspotRecovery({ eligible }, { false }, { calls++; Result.READY }, { it() })
        recovery.start()
        advance(10)
        assertEquals(0, calls)
        recovery.stop()
        eligible = true
        recovery.start()
        advance(5)
        assertEquals(1, calls)
        recovery.stop()
    }

    @Test fun slowRequestDoesNotOverlapAndCancellationTracksTheVisibleGeneration() {
        val work = mutableListOf<() -> Unit>()
        var calls = 0
        var cancelled: (() -> Boolean)? = null
        val recovery = ForegroundHotspotRecovery({ true }, { false }, {
            calls++; cancelled = it; Result.READY
        }, { work += it })
        recovery.start()
        advance(15)
        assertEquals(1, work.size)
        work.removeAt(0)()
        assertEquals(1, calls)
        assertFalse(cancelled!!())
        recovery.stop()
        assertTrue(cancelled!!())
        advance(20)
        assertTrue(work.isEmpty())
    }

    @Test fun closingBeforeWorkerStartsCancelsTheStartup() {
        var work: (() -> Unit)? = null
        var calls = 0
        val recovery = ForegroundHotspotRecovery({ true }, { false }, { calls++; Result.READY }, { work = it })
        recovery.start()
        advance(5)
        recovery.stop()
        work!!()
        advance(10)
        assertEquals(0, calls)
    }

    @Test fun failuresBackOffRatherThanRepeatedlyStartingTheHotspot() {
        var calls = 0
        val recovery = ForegroundHotspotRecovery({ true }, { false }, { calls++; error("Unavailable") }, { it() })
        recovery.start()
        advance(5)
        assertEquals(1, calls)
        advance(55)
        assertEquals(1, calls)
        advance(5)
        assertEquals(2, calls)
        recovery.stop()
    }
}
