package com.shilapi.xcertplay.vehicle.leapmotor

import org.junit.Assert.*
import org.junit.Test

class LeapmotorClimateShellProbeTest {
    private val now = 100_000_000_000L
    private class Port : LeapmotorClimateShellProbe.Port {
        var permission = true
        var configReads = 0
        var closed = 0
        var revokeOnRead = false
        var failure = false
        val areas = mutableListOf<Int>()
        var timestamp = 99_000_000_000L
        override fun permitted() = permission
        override fun configurations(): List<LeapmotorClimateConfiguration> {
            configReads++
            return listOf(LeapmotorClimateConfiguration(358614275, 3, java.lang.Float::class.java, 1,
                listOf(LeapmotorClimateArea(49, 16f, 32f), LeapmotorClimateArea(68, 16f, 32f))))
        }
        override fun read(area: Int): CarPropertyReading {
            areas += area
            if (failure) error("arbitrary sensitive exception text")
            if (revokeOnRead) permission = false
            return CarPropertyReading(358614275, area, 0, timestamp, if (area == 49) 16f else 22.5f)
        }
        override fun close() { closed++ }
    }
    private fun inspect(port: Port) = LeapmotorClimateShellProbe.inspect(2000, { now }) { port }

    @Test fun onlyRealShellUidCanOpenAndDeniedPermissionDoesNoPropertyIo() {
        var opens = 0
        for (uid in listOf(0, 1000, 1002000, 10123)) {
            val result = LeapmotorClimateShellProbe.inspect(uid, { now }) { opens++; Port() }
            assertTrue(result.single().contains("IDENTITY_REJECTED"))
        }
        assertEquals(0, opens)
        val denied = Port().apply { permission = false }
        val result = inspect(denied)
        assertEquals(0, denied.configReads)
        assertTrue(denied.areas.isEmpty())
        assertTrue(result.any { it.endsWith("before=false") })
        assertTrue(result.any { it.endsWith("after=false") })
        assertEquals(1, denied.closed)
    }

    @Test fun observedHalRangeAndOemConsumerRangeStayDistinctWithoutInventingStep() {
        val port = Port()
        val result = inspect(port)
        assertEquals(listOf(49, 68), port.areas)
        assertTrue(result.any { it.contains("min=16.0|max=32.0") })
        assertTrue(result.any { it.contains("requestedArea=49") && it.contains("value=16.0") &&
            it.contains("inHalRange=true|inOemUiRange=false") })
        assertTrue(result.any { it.contains("requestedArea=68") && it.contains("value=22.5") &&
            it.contains("oemTimestampNanos=99000000000") && it.contains("receivedAgeMs=1000|time=RECENT") })
        assertTrue(result.any { it.contains("stepKnown=false") })
        assertEquals(1, port.closed)
    }

    @Test fun zeroAndOldOnChangeTimestampsAreReportedRatherThanRefreshedOrCalledPhysicalProof() {
        val zero = inspect(Port().apply { timestamp = 0 })
        assertTrue(zero.filter { it.contains("|READ|") }.all { it.contains("receivedAgeMs=UNKNOWN|time=ZERO") })
        val old = inspect(Port().apply { timestamp = 1 })
        assertTrue(old.filter { it.contains("|READ|") }.all { it.contains("time=OLD_ON_CHANGE") })
        val future = inspect(Port().apply { timestamp = now + 1 })
        assertTrue(future.filter { it.contains("|READ|") }.all { it.contains("time=INVALID") })
    }

    @Test fun revocationDuringGetSuppressesValueAndAlwaysCloses() {
        val port = Port().apply { revokeOnRead = true }
        val result = inspect(port)
        assertTrue(result.any { it.contains("PERMISSION_REVOKED") })
        assertFalse(result.any { it.contains("|READ|") })
        assertTrue(result.any { it.endsWith("after=false") })
        assertEquals(1, port.closed)
    }

    @Test fun arbitraryExceptionTextCannotReachDiagnosticOutput() {
        val port = Port().apply { failure = true }
        val result = inspect(port).joinToString("\n")
        assertTrue(result.contains("UNAVAILABLE|stage=READ"))
        assertFalse(result.contains("arbitrary"))
        assertFalse(result.contains("sensitive"))
        assertEquals(1, port.closed)
    }

    class BinderContract {
        var requestedIds: IntArray? = null
        val reads = mutableListOf<Pair<Int, Int>>()
        fun getPropertyConfigList(ids: IntArray): List<Any> { requestedIds = ids.copyOf(); return emptyList() }
        fun getProperty(id: Int, area: Int): Any? { reads += id to area; return null }
        fun setProperty(ignored: Any): Nothing = error("Setter must never be called")
    }

    @Test fun binderFacadeRequestsOnlyHvacConfigAndWhitelistedGetAreasAndClosesWithoutSetters() {
        val binder = BinderContract()
        val facade = LeapmotorClimateShellReadMain.ReadOnlyPropertyFacade(BinderContract::class.java, binder)
        facade.getPropertyList()
        assertArrayEquals(intArrayOf(358614275), binder.requestedIds)
        facade.getProperty(358614275, 49)
        facade.getProperty(358614275, 68)
        assertThrows(IllegalArgumentException::class.java) { facade.getProperty(291505923, 0) }
        assertThrows(IllegalArgumentException::class.java) { facade.getProperty(358614275, 0) }
        assertEquals(listOf(358614275 to 49, 358614275 to 68), binder.reads)
        assertFalse(facade.javaClass.methods.any { it.name.startsWith("set") })
        facade.close(); facade.close()
        assertThrows(IllegalStateException::class.java) { facade.getProperty(358614275, 49) }
    }
}
