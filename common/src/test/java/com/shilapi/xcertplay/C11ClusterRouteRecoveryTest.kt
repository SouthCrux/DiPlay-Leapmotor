package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class C11ClusterRouteRecoveryTest {
    private class Host {
        var enabled = true
        var route = false
        var attempts = 0
        var restored = 0
        var completion: (() -> Unit)? = null
        var timer: (() -> Unit)? = null
        val delays = mutableListOf<Long>()
        val recovery = C11ClusterRouteRecovery({ enabled }, { route }, {
            attempts++; completion = it
        }, { delay, callback -> delays += delay; timer = callback }, { timer = null }, { restored++ })
        fun complete(verified: Boolean) { route = verified; completion!!.also { completion = null }.invoke() }
        fun tick() { timer!!.also { timer = null }.invoke() }
    }

    @Test fun lateAdbOrDisplayIsRetriedUntilPhysicalRouteCanBeVerified() {
        val host = Host()
        host.recovery.start()
        assertEquals(1, host.attempts)
        host.complete(false)
        host.tick()
        host.complete(false)
        host.tick()
        host.complete(true)
        assertEquals(listOf(1_000L, 2_000L), host.delays)
        assertEquals(1, host.restored)
        assertNull(host.timer)
        host.recovery.request()
        assertEquals(3, host.attempts)
    }

    @Test fun pauseCancelsRetriesAndLateCompletionDoesNotRestoreAHiddenHost() {
        val host = Host()
        host.recovery.start()
        host.recovery.request()
        assertEquals("Display events must not launch concurrent ADB probes", 1, host.attempts)
        host.recovery.stop()
        host.complete(true)
        assertEquals(0, host.restored)
        assertNull(host.timer)
        host.route = false
        host.recovery.start()
        host.complete(false)
        host.recovery.stop()
        assertNull(host.timer)
    }

    @Test fun retriesAreBoundedAndRespectDisabledSettingAndAlreadyValidRoute() {
        val host = Host()
        host.enabled = false
        host.recovery.start()
        assertEquals(0, host.attempts)
        host.enabled = true
        host.recovery.start()
        repeat(8) { host.complete(false); host.tick() }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), host.delays)
        host.complete(true)
        host.recovery.stop()
        host.recovery.start()
        assertEquals(9, host.attempts)
    }
}
