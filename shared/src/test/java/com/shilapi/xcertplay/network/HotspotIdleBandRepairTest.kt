package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class HotspotIdleBandRepairTest {
    private data class Config(val idle: Boolean = true, val other: Int = 17, val user: Boolean = true)
    private class Port : HotspotIdleBandRepair.Port<Config> {
        var config = Config()
        var reads = 0
        var writes = 0
        var corruptCopy = false
        var concurrent = false
        var reject = false
        var normalize = false
        override fun read(): Config {
            reads++
            if (concurrent && reads == 2) config = config.copy(other = 99)
            return config
        }
        override fun idleShutdown(config: Config) = config.idle
        override fun isUserConfiguration(config: Config) = config.user
        override fun withIdleShutdown(config: Config, enabled: Boolean) =
            config.copy(idle = enabled, other = if (corruptCopy) 18 else config.other)
        override fun write(config: Config): Boolean {
            writes++
            if (reject) return false
            this.config = if (normalize) config.copy(other = 21) else config
            return true
        }
    }
    @Test fun disablesAndRestoresOnlyIdleBandFlag() {
        val port = Port(); val original = port.config; val repair = HotspotIdleBandRepair(port)
        assertEquals(HotspotIdleBandRepair.Result.APPLIED, repair.set(false))
        assertEquals(original.copy(idle = false), port.config)
        assertEquals(HotspotIdleBandRepair.Result.APPLIED, repair.set(true))
        assertEquals(original, port.config)
    }
    @Test fun repeatedApplyDoesNotWriteAgain() {
        val port = Port(); val repair = HotspotIdleBandRepair(port)
        repair.set(false)
        assertEquals(HotspotIdleBandRepair.Result.UNCHANGED, repair.set(false))
        assertEquals(1, port.writes)
    }
    @Test fun nonRestorableConfigIsRefused() {
        val port = Port(); port.config = port.config.copy(user = false)
        assertEquals(HotspotIdleBandRepair.Result.REFUSED, HotspotIdleBandRepair(port).set(false))
        assertEquals(0, port.writes)
    }
    @Test fun copyThatChangesAnotherFieldIsRefused() {
        val port = Port(); port.corruptCopy = true
        assertEquals(HotspotIdleBandRepair.Result.REFUSED, HotspotIdleBandRepair(port).set(false))
        assertEquals(0, port.writes)
    }
    @Test fun concurrentSettingsChangeIsPreserved() {
        val port = Port(); port.concurrent = true
        assertEquals(HotspotIdleBandRepair.Result.REFUSED, HotspotIdleBandRepair(port).set(false))
        assertEquals(99, port.config.other); assertEquals(0, port.writes)
    }
    @Test fun platformRejectionIsNotReportedAsApplied() {
        val port = Port(); port.reject = true
        assertEquals(HotspotIdleBandRepair.Result.REJECTED, HotspotIdleBandRepair(port).set(false))
        assertTrue(port.config.idle)
    }
    @Test fun readbackMismatchIsNotReportedAsApplied() {
        val port = Port(); port.normalize = true
        assertEquals(HotspotIdleBandRepair.Result.VERIFY_FAILED, HotspotIdleBandRepair(port).set(false))
    }
}
