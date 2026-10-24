package com.shilapi.xcertplay.vehicle.leapmotor

import org.junit.Assert.*
import org.junit.Test

class ReflectiveLeapmotorClimateAccessTest {
    class Config(private val id: Int) {
        fun getPropertyId() = id
        fun getAccess() = 3
        fun getPropertyType(): Class<*> = java.lang.Float::class.java
        fun getChangeMode() = 1
        fun getAreaIds() = intArrayOf(49, 68, 999)
        fun getMinValue(area: Int) = if (area == 49 || area == 68) 16f else error("unrelated area")
        fun getMaxValue(area: Int) = if (area == 49 || area == 68) 32f else error("unrelated area")
    }
    class Value {
        fun getPropertyId() = 358614275
        fun getAreaId() = 68
        fun getStatus() = 0
        fun getTimestamp() = 123L
        fun getValue() = 22f
    }
    class Manager {
        val writes = mutableListOf<Triple<Int, Int, Float>>()
        var denied = false
        fun getPropertyList() = listOf(Config(123), Config(358614275))
        fun getProperty(id: Int, area: Int): Value {
            if (denied) throw SecurityException("denied")
            check(id == 358614275 && area == 68)
            return Value()
        }
        fun setFloatProperty(id: Int, area: Int, value: Float) {
            if (denied) throw SecurityException("denied")
            writes += Triple(id, area, value)
        }
    }

    @Test fun onlyTemperaturePropertyAndVerifiedSeatAreasAreExposed() {
        val manager = Manager()
        val access = ReflectiveLeapmotorClimateAccess(manager)
        val config = access.configurations().single()
        assertEquals(358614275, config.propertyId)
        assertEquals(listOf(49, 68), config.areas.map { it.areaId })
        assertEquals(CarPropertyReading(358614275, 68, 0, 123, 22f), access.readTarget(68))
        access.writeTarget(49, 23.5f)
        assertEquals(listOf(Triple(358614275, 49, 23.5f)), manager.writes)
    }

    @Test fun reflectionPreservesDenialForBothReadAndWrite() {
        val manager = Manager().apply { denied = true }
        val access = ReflectiveLeapmotorClimateAccess(manager)
        assertThrows(SecurityException::class.java) { access.readTarget(68) }
        assertThrows(SecurityException::class.java) { access.writeTarget(49, 23f) }
        assertTrue(manager.writes.isEmpty())
    }

    @Test fun unrelatedAreasAndNonfiniteWritesAreRejectedBeforeCallingManager() {
        val manager = Manager()
        val access = ReflectiveLeapmotorClimateAccess(manager)
        assertThrows(IllegalArgumentException::class.java) { access.readTarget(0) }
        assertThrows(IllegalArgumentException::class.java) { access.writeTarget(999, 23f) }
        assertThrows(IllegalArgumentException::class.java) { access.writeTarget(49, Float.NaN) }
        assertTrue(manager.writes.isEmpty())
    }
}
