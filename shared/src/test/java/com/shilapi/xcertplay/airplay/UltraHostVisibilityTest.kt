package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class UltraHostVisibilityTest {
    @Test fun foregroundRequiresBothResumedHostAndReadySurface() {
        val state = UltraHostVisibility()
        assertEquals(false, state.takePending())
        state.update(resumed = true, surfaceReady = false)
        assertNull(state.takePending())
        state.update(resumed = false, surfaceReady = true)
        assertNull(state.takePending())
        state.update(resumed = true, surfaceReady = true)
        assertEquals(true, state.takePending())
        state.update(resumed = true, surfaceReady = false)
        assertEquals(false, state.takePending())
        state.update(resumed = true, surfaceReady = true)
        assertEquals(true, state.takePending())
        state.update(resumed = false, surfaceReady = true)
        assertEquals(false, state.takePending())
    }

    @Test fun callbacksCoalesceToLatestSnapshotWithoutQueueingEveryEvent() {
        val state = UltraHostVisibility()
        repeat(10_000) {
            state.update(resumed = true, surfaceReady = true)
            state.update(resumed = false, surfaceReady = true)
        }
        state.update(resumed = true, surfaceReady = true)
        assertEquals(true, state.takePending())
        assertNull(state.takePending())
        state.update(resumed = true, surfaceReady = true)
        assertNull(state.takePending())
    }

    @Test fun retiredConnectionDiscardsPendingAndLateCallbacksWithoutTouchingReplacement() {
        val old = UltraHostVisibility()
        old.update(resumed = true, surfaceReady = true)
        old.close()
        val replacement = UltraHostVisibility()
        old.update(resumed = false, surfaceReady = false)
        old.update(resumed = true, surfaceReady = true)
        old.close()
        assertNull(old.takePending())
        assertEquals(false, replacement.takePending())
        replacement.update(resumed = true, surfaceReady = true)
        assertEquals(true, replacement.takePending())
    }
}
