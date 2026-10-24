package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayVideoParkedSourceTest {
    private class Source(var value: Boolean? = true) : CarPlayVideoParkedSource {
        var closes = 0
        var onRead: () -> Unit = {}
        override fun readParked(): Boolean? { onRead(); return value }
        override fun close() { closes++ }
    }

    @Test fun explicitFactoryDoesNotAcquireBeforeARealSessionOrAfterItEnds() {
        var acquired = 0
        val source = Source()
        val parked = CarPlayVideoParkedSession({ acquired++; source }) { error("BYD fallback forbidden") }
        assertNull(parked.readParked())
        assertEquals(0, acquired)
        parked.begin()
        assertEquals(0, acquired)
        assertEquals(true, parked.readParked())
        assertEquals(true, parked.readParked())
        assertEquals(1, acquired)
        parked.end()
        assertEquals(1, source.closes)
        assertNull(parked.readParked())
        parked.close()
        parked.close()
        assertEquals(1, source.closes)
        assertEquals(1, acquired)
    }

    @Test fun replacementSessionClosesPreviousSourceAndAcquiresItsOwn() {
        val sources = mutableListOf<Source>()
        val parked = CarPlayVideoParkedSession({ Source().also(sources::add) }) { null }
        parked.begin()
        assertEquals(true, parked.readParked())
        parked.begin()
        assertEquals(1, sources.single().closes)
        assertEquals(true, parked.readParked())
        assertEquals(2, sources.size)
        parked.close()
        assertEquals(listOf(1, 1), sources.map { it.closes })
    }

    @Test fun failedFactoryIsUnknownWithoutRetryingOrUsingOrdinaryFallback() {
        var attempts = 0
        val parked = CarPlayVideoParkedSession({ attempts++; error("unavailable") }) { true }
        parked.begin()
        repeat(4) { assertNull(parked.readParked()) }
        assertEquals(1, attempts)
        parked.end()
        parked.begin()
        assertNull(parked.readParked())
        assertEquals(2, attempts)
        parked.close()
    }

    @Test fun sourceAcquiredAfterCancellationIsClosedInsteadOfAdopted() {
        val source = Source()
        lateinit var parked: CarPlayVideoParkedSession
        parked = CarPlayVideoParkedSession({ parked.end(); source }) { true }
        parked.begin()
        assertNull(parked.readParked())
        assertEquals(1, source.closes)
        parked.close()
        assertEquals(1, source.closes)
    }

    @Test fun sourceReadReturningAfterRetirementCannotReportParkedToReplacement() {
        val old = Source()
        val parked = CarPlayVideoParkedSession({ old }) { null }
        old.onRead = { parked.end() }
        parked.begin()
        assertNull(parked.readParked())
        assertEquals(1, old.closes)
        parked.close()
    }

    @Test fun ordinaryBackendBehaviorIsPreservedWithoutAcquiringAnotherSource() {
        var ordinary: Boolean? = true
        val parked = CarPlayVideoParkedSession(null) { ordinary }
        assertEquals(true, parked.readParked())
        parked.begin()
        ordinary = false
        assertEquals(false, parked.readParked())
        parked.end()
        ordinary = null
        assertNull(parked.readParked())
        parked.close()
        ordinary = true
        assertNull(parked.readParked())
    }
}
