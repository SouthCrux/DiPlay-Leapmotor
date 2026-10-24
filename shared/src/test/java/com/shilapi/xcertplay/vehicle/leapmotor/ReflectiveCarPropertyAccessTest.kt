package com.shilapi.xcertplay.vehicle.leapmotor

import org.junit.Assert.*
import org.junit.Test

class ReflectiveCarPropertyAccessTest {
    private val now = 100_000_000_000L

    // Public JVM fixtures with the extracted Android Car API's method signatures. No android.car JAR.
    class Config(val propertyId: Int) {
        fun getAccess() = 1
        fun isGlobalProperty() = true
        fun getPropertyType(): Class<*> = java.lang.Float::class.java
        fun getChangeMode() = 2
    }
    class Value(val propertyId: Int, val areaId: Int, val timestamp: Long, val value: Float) {
        fun getStatus() = 0
    }
    class Manager(private val time: Long) {
        var denied = false
        var offline = false
        val reads = mutableListOf<Pair<Int, Int>>()
        fun getPropertyList(): List<Config> {
            if (offline) throw IllegalStateException("lost service")
            return listOf(Config(123), Config(LeapmotorEvProperty.RANGE_METERS.id))
        }
        fun getProperty(id: Int, area: Int): Value {
            if (denied) throw SecurityException("permission lost")
            reads += id to area
            return Value(id, area, time, 45_500f)
        }
    }

    @Test fun adaptsTheExtractedReadApiWithoutDependingOnOrBundlingOemClasses() {
        val manager = Manager(now)
        val access = ReflectiveCarPropertyAccess(manager)
        assertEquals(listOf(LeapmotorEvProperty.RANGE_METERS.id), access.configurations().map { it.id })
        val result = LeapmotorCarPropertyReader(access) { now }.read()
        assertEquals(45.5, result.rangeKm!!, 0.0)
        assertNull(result.currentEnergyWh)
        assertEquals(listOf(LeapmotorEvProperty.RANGE_METERS.id to 0), manager.reads)
    }

    @Test fun invocationWrapperPreservesPermissionLossAndConnectionFailure() {
        val manager = Manager(now)
        val reader = LeapmotorCarPropertyReader(ReflectiveCarPropertyAccess(manager)) { now }
        assertNotNull(reader.read().rangeKm)
        manager.denied = true
        assertEquals(PropertyReadState.ACCESS_DENIED, reader.read().fields.getValue(LeapmotorEvProperty.RANGE_METERS).state)
        manager.offline = true
        assertTrue(reader.read().fields.values.all { it.state == PropertyReadState.ERROR })
    }

    @Test fun missingApiFailsClosedWithoutAttemptingAnAlternativeService() {
        val reader = LeapmotorCarPropertyReader(ReflectiveCarPropertyAccess(Any())) { now }
        assertTrue(reader.read().fields.values.all { it.state == PropertyReadState.ERROR })
    }
}
