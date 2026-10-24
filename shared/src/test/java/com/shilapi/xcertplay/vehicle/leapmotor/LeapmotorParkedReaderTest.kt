package com.shilapi.xcertplay.vehicle.leapmotor

import org.junit.Assert.*
import org.junit.Test

class LeapmotorParkedReaderTest {
    private var now = 100_000_000_000L
    private var raw: CarPropertyReading? = CarPropertyReading(289408000, 0, 0, now - 1, 4)
    private var configs = listOf(CarPropertyDescriptor(289408000, 1, true, java.lang.Integer::class.java, 1))
    private var denied = false
    private val reader = LeapmotorParkedReader(object : ReadOnlyLeapmotorGearAccess {
        override fun configurations(): List<CarPropertyDescriptor> {
            if (denied) throw SecurityException()
            return configs
        }
        override fun readGear() = raw
    }, elapsedNanos = { now })

    @Test fun onlyFreshValidatedParkGearBecomesTrueAndOtherKnownGearsBecomeFalse() {
        assertEquals(true, reader.read().parked)
        listOf(1, 2, 8, 16, 32, 64, 128, 256).forEach {
            raw = raw!!.copy(value = it)
            assertEquals(false, reader.read().parked)
        }
    }
    @Test fun rereadingOldOnChangeEventDoesNotRenewItsTimestamp() {
        val timestamp = raw!!.timestampNanos
        now += 5_000_000_001L
        repeat(2) {
            val result = reader.read()
            assertEquals(PropertyReadState.STALE, result.state)
            assertNull(result.parked)
            assertEquals(timestamp, result.timestampNanos)
        }
    }
    @Test fun zeroFutureUnknownWrongTypeOrWrongAreaNeverBecomesParked() {
        val initial = raw!!
        listOf(initial.copy(timestampNanos = 0), initial.copy(timestampNanos = now + 1),
            initial.copy(value = 0), initial.copy(value = 3), initial.copy(value = 4f),
            initial.copy(areaId = 49), initial.copy(id = 289408001), initial.copy(status = 1),
            initial.copy(status = 2)).forEach {
            raw = it
            assertNull(reader.read().parked)
        }
    }
    @Test fun missingReadOrPermissionLossClearsResultWithoutLastKnownPark() {
        assertEquals(true, reader.read().parked)
        raw = null
        assertEquals(PropertyReadState.UNAVAILABLE, reader.read().state)
        denied = true
        assertEquals(PropertyReadState.ACCESS_DENIED, reader.read().state)
        assertNull(reader.read().parked)
    }
    @Test fun missingDuplicateOrIncompatibleConfigPreventsParkedReading() {
        val c = configs.single()
        listOf(emptyList(), listOf(c, c), listOf(c.copy(global = false)),
            listOf(c.copy(type = java.lang.Float::class.java)), listOf(c.copy(access = 2)),
            listOf(c.copy(changeMode = 2))).forEach {
            configs = it
            assertNull(reader.read().parked)
        }
    }
    @Test fun currentHalSnapshotKeepsOldEventTimeSeparateFromFreshReadDelivery() {
        raw = raw!!.copy(timestampNanos = 1)
        val snapshots = LeapmotorParkedReader(object : ReadOnlyLeapmotorGearAccess {
            override fun configurations() = configs
            override fun readGear() = raw
        }, elapsedNanos = { now }, freshness = LeapmotorGearFreshness.CURRENT_HAL_SNAPSHOT)
        val first = snapshots.read()
        assertEquals(true, first.parked)
        assertEquals(1L, first.timestampNanos)
        assertEquals(now, first.halReadCompletedAtNanos)
        now += 10_000_000_000L
        val next = snapshots.read()
        assertEquals(true, next.parked)
        assertEquals(1L, next.timestampNanos)
        assertEquals(now, next.halReadCompletedAtNanos)
    }
    @Test fun currentSnapshotRejectsSlowGetAndInvalidTimestampRatherThanRenewingStaleDelivery() {
        var delay = 5_000_000_001L
        val snapshots = LeapmotorParkedReader(object : ReadOnlyLeapmotorGearAccess {
            override fun configurations() = configs
            override fun readGear(): CarPropertyReading? { now += delay; return raw }
        }, elapsedNanos = { now }, freshness = LeapmotorGearFreshness.CURRENT_HAL_SNAPSHOT)
        assertEquals(PropertyReadState.STALE, snapshots.read().state)
        delay = 0
        listOf(0L, now + 1).forEach { timestamp ->
            raw = raw!!.copy(timestampNanos = timestamp)
            assertNull(snapshots.read().parked)
        }
    }
    class Manager {
        var reads = 0
        fun getPropertyList() = listOf(Configuration())
        fun getProperty(id: Int, area: Int): Reading {
            check(id == 289408000 && area == 0)
            reads++
            return Reading()
        }
    }
    class Configuration {
        fun getPropertyId() = 289408000
        fun getAccess() = 1
        fun isGlobalProperty() = true
        fun getPropertyType(): Class<*> = java.lang.Integer::class.java
        fun getChangeMode() = 1
    }
    class Reading {
        fun getPropertyId() = 289408000
        fun getAreaId() = 0
        fun getStatus() = 0
        fun getTimestamp() = 99_999_999_999L
        fun getValue() = 4
    }
    @Test fun reflectionAdapterRequestsOnlyVerifiedGlobalGearProperty() {
        val manager = Manager()
        val adapter = ReflectiveLeapmotorGearAccess(manager)
        assertEquals(289408000, adapter.configurations().single().id)
        assertEquals(4, adapter.readGear()!!.value)
        assertEquals(1, manager.reads)
    }
}
