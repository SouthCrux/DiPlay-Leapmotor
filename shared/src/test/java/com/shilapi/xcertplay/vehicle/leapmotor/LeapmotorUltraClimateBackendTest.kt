package com.shilapi.xcertplay.vehicle.leapmotor

import com.shilapi.xcertplay.airplay.UltraClimateBackend.Zone
import com.shilapi.xcertplay.airplay.UltraClimateBackend.Seat
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable

class LeapmotorUltraClimateBackendTest {
    private var now = 100_000_000_000L
    private val access = Access()
    private val worker = Worker()
    // Intentionally right-hand driver; the adapter must not assume LEFT means DRIVER.
    private val mapping = mapOf(Zone.DRIVER to LeapmotorClimateSide.RIGHT,
        Zone.PASSENGER to LeapmotorClimateSide.LEFT)

    private inner class Access : LeapmotorClimateAccess {
        var reads = 0
        var denied = false
        val values = mutableMapOf(49 to 22f, 68 to 23f)
        var timestamp = now - 1
        var onWrite: () -> Unit = {}
        val writes = mutableListOf<Pair<Int, Float>>()
        override fun configurations(): List<LeapmotorClimateConfiguration> {
            if (denied) throw SecurityException()
            return listOf(LeapmotorClimateConfiguration(358614275, 3, java.lang.Float::class.java, 1,
                listOf(LeapmotorClimateArea(49, 16f, 32f), LeapmotorClimateArea(68, 16f, 32f))))
        }
        override fun readTarget(areaId: Int): CarPropertyReading {
            reads++
            return CarPropertyReading(358614275, areaId, 0, timestamp, values[areaId])
        }
        override fun writeTarget(areaId: Int, celsius: Float) {
            onWrite()
            writes += areaId to celsius
        }
    }
    private class Worker : LeapmotorClimateWorker {
        data class Work(val delay: Long, val action: () -> Unit, var canceled: Boolean = false)
        val tasks = mutableListOf<Work>()
        var closed = false
        override fun execute(task: () -> Unit): Closeable = schedule(0, task)
        override fun schedule(delayMillis: Long, task: () -> Unit): Closeable {
            check(!closed)
            val work = Work(delayMillis, task)
            tasks += work
            return Closeable { work.canceled = true }
        }
        fun next() {
            tasks.removeAll { it.canceled }
            val task = tasks.minByOrNull { it.delay } ?: return
            tasks.remove(task)
            task.action()
            // Timer callbacks only queue I/O; drain immediate work without advancing timers.
            while (true) {
                tasks.removeAll { it.canceled }
                val immediate = tasks.firstOrNull { it.delay == 0L } ?: return
                tasks.remove(immediate)
                immediate.action()
            }
        }
        fun deadline() {
            val task = tasks.single { !it.canceled && it.delay == 2000L }
            tasks.remove(task)
            task.action()
        }
        override fun close() { closed = true; tasks.clear() }
    }
    private fun backend(
        enabled: Boolean = true, steps: Boolean = true,
        zones: Map<Zone, LeapmotorClimateSide> = mapping,
    ): LeapmotorUltraClimateBackend {
        val controller = LeapmotorClimateController(access, enabled,
            if (steps) LeapmotorClimateSide.entries.associateWith { 0.5 } else emptyMap(),
            elapsedNanos = { now })
        return LeapmotorUltraClimateBackend(controller, zones, worker, { now },
            5_000_000_000L, 2_000_000_000L, 250)
    }
    private fun ready(backend: LeapmotorUltraClimateBackend) {
        assertTrue(backend.refresh())
        worker.next()
    }

    @Test fun missingMappingOrVerifiedStepCannotAdvertiseCapabilitiesOrWrite() {
        val unmapped = backend(zones = emptyMap())
        assertFalse(unmapped.refresh())
        assertTrue(unmapped.capabilities().isEmpty())
        assertEquals(0, access.reads)
        val unverified = backend(steps = false)
        ready(unverified)
        assertTrue(unverified.capabilities().isEmpty())
        assertNull(unverified.snapshot())
        val results = mutableListOf<Boolean>()
        unverified.requestTemperature(Zone.DRIVER, 24.0, results::add)
        assertEquals(listOf(false), results)
        assertTrue(access.writes.isEmpty())
    }

    @Test fun cachedGettersNeverBlockOrPollAndRefreshIsBounded() {
        val backend = backend()
        assertTrue(backend.refresh())
        assertFalse(backend.refresh())
        assertNull(backend.snapshot())
        worker.next()
        val reads = access.reads
        repeat(20) {
            assertEquals(2, backend.capabilities().size)
            assertEquals(23.0, backend.snapshot()!!.targetsCelsius.getValue(Zone.DRIVER), 0.0)
        }
        assertEquals(reads, access.reads)
        now += 5_000_000_001L
        assertTrue(backend.capabilities().isEmpty())
        assertNull(backend.snapshot())
    }

    @Test fun fullRouteUsesVerifiedPhysicalMappingAndOnlyCompletesAfterReadback() {
        val backend = backend()
        ready(backend)
        assertEquals(Seat.FRONT_RIGHT, backend.capabilities().single { it.zone == Zone.DRIVER }.seat)
        assertEquals(Seat.FRONT_LEFT, backend.capabilities().single { it.zone == Zone.PASSENGER }.seat)
        val results = mutableListOf<Boolean>()
        backend.requestTemperature(Zone.DRIVER, 24.5, results::add)
        assertTrue(access.writes.isEmpty()) // API call itself is nonblocking and does no I/O.
        worker.next()
        assertEquals(listOf(68 to 24.5f), access.writes)
        assertTrue(results.isEmpty())
        assertEquals(23.0, backend.snapshot()!!.targetsCelsius.getValue(Zone.DRIVER), 0.0)
        access.values[68] = 24.5f
        now += 250_000_000L
        access.timestamp = now
        worker.next()
        assertEquals(listOf(true), results)
        assertEquals(24.5, backend.snapshot()!!.targetsCelsius.getValue(Zone.DRIVER), 0.0)
    }

    @Test fun timeoutAndLateReadbackCannotProduceSuccess() {
        val backend = backend()
        ready(backend)
        val results = mutableListOf<Boolean>()
        backend.requestTemperature(Zone.DRIVER, 24.0, results::add)
        worker.next()
        now += 2_000_000_000L
        worker.next()
        assertEquals(listOf(false), results)
        access.values[68] = 24f
        access.timestamp = now
        ready(backend)
        assertEquals(listOf(false), results)
    }

    @Test fun canceledOrExpiredQueuedIntentDoesNotReachSetter() {
        val backend = backend()
        ready(backend)
        val results = mutableListOf<Boolean>()
        val request = backend.requestTemperature(Zone.DRIVER, 24.0, results::add)
        request.close()
        request.close()
        worker.next()
        assertEquals(listOf(false), results)
        backend.requestTemperature(Zone.DRIVER, 24.0, results::add)
        now += 2_000_000_000L
        worker.next()
        assertEquals(listOf(false, false), results)
        assertTrue(access.writes.isEmpty())
    }

    @Test fun onlyOnePendingRequestAndCallbacksCanCloseReentrantly() {
        val backend = backend()
        ready(backend)
        val results = mutableListOf<Boolean>()
        val first = backend.requestTemperature(Zone.DRIVER, 24.0) { results += it; backend.close() }
        backend.requestTemperature(Zone.PASSENGER, 24.0, results::add)
        assertEquals(listOf(false), results)
        first.close()
        assertEquals(listOf(false, false), results)
        assertTrue(worker.closed)
        assertTrue(access.writes.isEmpty())
        assertNull(backend.snapshot())
        assertFalse(backend.refresh())
    }

    @Test fun accessLossAndDisabledControllerClearPreviouslyAdvertisedCapabilities() {
        val backend = backend()
        ready(backend)
        assertEquals(2, backend.capabilities().size)
        access.denied = true
        ready(backend)
        assertTrue(backend.capabilities().isEmpty())
        assertNull(backend.snapshot())
        access.denied = false
        val disabled = backend(enabled = false)
        ready(disabled)
        assertTrue(disabled.capabilities().isEmpty())
    }

    @Test fun closingQueuedRefreshPreventsAllReadsAndRepeatedCloseIsSafe() {
        val backend = backend()
        backend.refresh()
        backend.close()
        backend.close()
        assertEquals(0, access.reads)
        assertTrue(worker.tasks.isEmpty())
        assertTrue(backend.capabilities().isEmpty())
    }

    @Test fun independentDeadlineCanFailWhileOemSetterHasNotReturned() {
        val backend = backend()
        ready(backend)
        val results = mutableListOf<Boolean>()
        access.onWrite = {
            now += 2_000_000_000L
            worker.deadline()
            assertEquals(listOf(false), results)
        }
        backend.requestTemperature(Zone.DRIVER, 24.0, results::add)
        worker.next()
        assertEquals(listOf(false), results)
        assertEquals(listOf(68 to 24f), access.writes)
        access.values[68] = 24f
        access.timestamp = now
        ready(backend)
        assertEquals(listOf(false), results)
    }
}
