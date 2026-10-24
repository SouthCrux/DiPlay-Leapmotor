package com.shilapi.xcertplay.vehicle.leapmotor

import com.shilapi.xcertplay.airplay.UltraClimateBackend.Zone
import com.shilapi.xcertplay.airplay.UltraClimateSessionRegistry
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

class LeapmotorClimateBackendFactoryTest {
    private var now = 100_000_000_000L
    private var permission = true
    private var firmware = "measured-c11-firmware"
    private var opens = 0
    private var closes = 0
    private val worker = Worker()
    private val access = Access()
    private val verified = LeapmotorClimateVerification(firmware,
        mapOf(Zone.DRIVER to LeapmotorClimateSide.RIGHT, Zone.PASSENGER to LeapmotorClimateSide.LEFT),
        LeapmotorClimateSide.entries.associateWith { 0.5 }, "fake fixture measurement; not car provisioning")

    private inner class Access : LeapmotorClimateAccess {
        var available = true
        var target = 22f
        var timestamp = now - 1
        var writes = 0
        var onWrite: () -> Unit = {}
        override fun configurations() = listOf(LeapmotorClimateConfiguration(358614275, 3,
            java.lang.Float::class.java, 1,
            listOf(LeapmotorClimateArea(49, 16f, 32f), LeapmotorClimateArea(68, 16f, 32f))))
        override fun readTarget(areaId: Int) = if (available)
            CarPropertyReading(358614275, areaId, 0, timestamp, target) else null
        override fun writeTarget(areaId: Int, celsius: Float) { writes++; onWrite() }
    }
    private class Worker : LeapmotorClimateWorker {
        data class Task(val delay: Long, val run: () -> Unit, var cancelled: Boolean = false)
        val tasks = mutableListOf<Task>()
        var closed = false
        override fun execute(task: () -> Unit) = schedule(0, task)
        override fun schedule(delayMillis: Long, task: () -> Unit): Closeable {
            check(!closed)
            val next = Task(delayMillis, task)
            tasks += next
            return Closeable { next.cancelled = true }
        }
        fun run(delay: Long) {
            val next = tasks.first { !it.cancelled && it.delay == delay }
            tasks.remove(next)
            next.run()
        }
        override fun close() { closed = true; tasks.clear() }
    }
    private fun factory() = LeapmotorClimateBackendFactory(verified, { firmware }, { permission }, {
        opens++
        LeapmotorClimateBackendFactory.OwnedAccess(access, Closeable { closes++ })
    }, { guarded, verification, authorization ->
        LeapmotorUltraClimateBackend(
            LeapmotorClimateController(guarded, true, verification.stepsBySide, elapsedNanos = { now }),
            verification.zoneMapping, worker, { now }, 5_000_000_000L, 2_000_000_000L, 250,
            authorization)
    })

    @Test fun missingPermissionOrDifferentFirmwareNeverOpensManager() {
        permission = false
        assertNull(factory().open())
        permission = true
        firmware = "updated firmware"
        assertNull(factory().open())
        assertEquals(0, opens)
    }

    @Test fun unavailableInitialReadingClosesManagerAndCannotAdvertise() {
        access.available = false
        assertNull(factory().open())
        assertEquals(1, opens)
        assertEquals(1, closes)
        assertTrue(worker.closed)
    }

    @Test fun openingIsImmediatelyServiceableAndRegistryRevocationOwnsManager() {
        val registry = UltraClimateSessionRegistry()
        val registration = registry.register(factory())
        val lease = registry.acquire()!!
        assertEquals(2, lease.backend.capabilities().size)
        assertEquals(22.0, lease.backend.snapshot()!!.targetsCelsius.getValue(Zone.DRIVER), 0.0)
        registration.close()
        registration.close()
        assertEquals(1, closes)
        assertTrue(worker.closed)
        assertTrue(lease.backend.capabilities().isEmpty())
        assertNull(registry.acquire())
    }

    @Test fun permissionRevocationWithdrawsCachedCapabilitiesAndQueuedWrite() {
        val owned = factory().open()!!
        val results = mutableListOf<Boolean>()
        owned.backend.requestTemperature(Zone.DRIVER, 24.0, results::add)
        permission = false
        assertTrue(owned.backend.capabilities().isEmpty())
        assertNull(owned.backend.snapshot())
        worker.run(0)
        assertEquals(0, access.writes)
        assertEquals(listOf(false), results)
        owned.owner.close()
        assertEquals(1, closes)
    }

    @Test fun permissionLostDuringSetterCannotConfirmAndMaintenanceClosesManager() {
        val owned = factory().open()!!
        val results = mutableListOf<Boolean>()
        access.onWrite = {
            permission = false
            now++
            access.timestamp = now
            access.target = 24f
        }
        owned.backend.requestTemperature(Zone.DRIVER, 24.0, results::add)
        worker.run(0)
        assertEquals(listOf(false), results)
        assertEquals(1, access.writes)
        worker.run(1_000)
        assertEquals(1, closes)
        assertTrue(worker.closed)
        owned.owner.close()
        assertEquals(1, closes)
    }

    @Test fun revokedLeaseCancelsQueuedCommandBeforeManagerClosure() {
        val registry = UltraClimateSessionRegistry()
        val registration = registry.register(factory())
        val lease = registry.acquire()!!
        val results = mutableListOf<Boolean>()
        lease.backend.requestTemperature(Zone.DRIVER, 24.0, results::add)
        registration.close()
        assertEquals(listOf(false), results)
        assertEquals(0, access.writes)
        assertEquals(1, closes)
        assertTrue(worker.tasks.isEmpty())
    }

    @Test fun firmwareChangeRevokesAnAlreadyOpenBackendWithoutReusingVerification() {
        val owned = factory().open()!!
        firmware = "changed"
        assertTrue(owned.backend.capabilities().isEmpty())
        val results = mutableListOf<Boolean>()
        owned.backend.requestTemperature(Zone.DRIVER, 24.0, results::add)
        assertEquals(listOf(false), results)
        assertEquals(0, access.writes)
        worker.run(1_000)
        assertEquals(1, closes)
    }
}
