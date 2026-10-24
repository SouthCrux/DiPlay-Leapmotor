package com.shilapi.xcertplay.vehicle.leapmotor

import org.junit.Assert.*
import org.junit.Test

class LeapmotorExteriorTemperatureReaderTest {
    private var now = 100_000_000_000L
    private val id = LeapmotorExteriorTemperatureReader.PROPERTY_ID
    private val config = CarPropertyDescriptor(id, 1, true, java.lang.Float::class.java, 2)
    private var configs = listOf(config)
    private var raw: CarPropertyReading? = CarPropertyReading(id, 0, 0, now, 0f)
    private var permission = true
    private var readDelay = 0L
    private var reads = 0
    private val access = object : ReadOnlyLeapmotorExteriorAccess {
        override fun configurations(): List<CarPropertyDescriptor> {
            if (!permission) throw SecurityException()
            return configs
        }
        override fun readExterior(): CarPropertyReading? { reads++; now += readDelay; return raw }
    }
    private val reader = LeapmotorExteriorTemperatureReader(access, elapsedNanos = { now })

    @Test fun zeroCelsiusIsRealOnlyWithAvailableFloatAndFreshOriginalTimestamp() {
        val result = reader.read()
        assertEquals(PropertyReadState.AVAILABLE, result.state)
        assertEquals(0f, result.celsius!!, 0f)
        assertEquals(now, result.timestampNanos)
        assertEquals(now, result.halReadCompletedAtNanos)
        raw = raw!!.copy(value = -12.5f)
        assertEquals(-12.5f, reader.read().celsius!!, 0f)
    }
    @Test fun changedConfigPermissionAndMissingPropertyWithdrawInsteadOfReadOrKeepLastGood() {
        reader.read()
        for (c in listOf(config.copy(access = 2), config.copy(access = 3), config.copy(global = false),
            config.copy(type = java.lang.Double::class.java), config.copy(changeMode = 1))) {
            configs = listOf(c)
            assertEquals(PropertyReadState.INVALID_CONFIG, reader.read().state)
        }
        configs = listOf(config, config)
        assertEquals(PropertyReadState.INVALID_CONFIG, reader.read().state)
        configs = emptyList()
        assertEquals(PropertyReadState.NOT_EXPOSED, reader.read().state)
        permission = false
        assertEquals(PropertyReadState.ACCESS_DENIED, reader.read().state)
        assertEquals(1, reads)
    }
    @Test fun statusesAndInvalidValuesNeverFabricateZeroOrRetainPreviousTemperature() {
        val initial = raw!!
        raw = null
        assertEquals(PropertyReadState.UNAVAILABLE, reader.read().state)
        raw = initial.copy(status = 1, value = null)
        assertEquals(PropertyReadState.UNAVAILABLE, reader.read().state)
        raw = initial.copy(status = 2, value = null)
        assertEquals(PropertyReadState.ERROR, reader.read().state)
        for (bad in listOf(initial.copy(id = id + 1), initial.copy(areaId = 1), initial.copy(value = 20.0),
            initial.copy(value = Float.NaN), initial.copy(value = Float.POSITIVE_INFINITY),
            initial.copy(value = null), initial.copy(timestampNanos = now + 1))) {
            raw = bad
            val result = reader.read()
            assertEquals(PropertyReadState.INVALID_VALUE, result.state)
            assertNull(result.celsius)
        }
    }
    @Test fun freshnessIsEvaluatedAfterBlockedGetWithoutOverwritingOldOemTimestamp() {
        val origin = now
        readDelay = 61_000_000_000L
        val stale = reader.read()
        assertEquals(PropertyReadState.STALE, stale.state)
        assertEquals(origin, stale.timestampNanos)
        assertEquals(now, stale.halReadCompletedAtNanos)
        assertNull(stale.celsius)
        readDelay = 0
        raw = raw!!.copy(timestampNanos = now - 59_000_000_000L)
        val result = reader.read()
        assertEquals(PropertyReadState.AVAILABLE, result.state)
        assertNotEquals(result.timestampNanos, result.halReadCompletedAtNanos)
        raw = raw!!.copy(timestampNanos = 0)
        assertEquals(PropertyReadState.STALE, reader.read().state)
    }
    class Manager {
        fun getPropertyList() = listOf(Configuration())
        fun getProperty(id: Int, area: Int): Value { check(id == 291505923 && area == 0); return Value() }
    }
    class Configuration {
        fun getPropertyId() = 291505923
        fun getAccess() = 1
        fun isGlobalProperty() = true
        fun getPropertyType(): Class<*> = java.lang.Float::class.java
        fun getChangeMode() = 2
    }
    class Value {
        fun getPropertyId() = 291505923
        fun getAreaId() = 0
        fun getStatus() = 0
        fun getTimestamp() = 99_000_000_000L
        fun getValue() = -3.25f
    }
    @Test fun reflectionUsesFullPropertyValueNotLossyFloatConvenienceWrapper() {
        val result = LeapmotorExteriorTemperatureReader(ReflectiveLeapmotorExteriorAccess(Manager()),
            elapsedNanos = { now }).read()
        assertEquals(PropertyReadState.AVAILABLE, result.state)
        assertEquals(-3.25f, result.celsius!!, 0f)
        assertEquals(99_000_000_000L, result.timestampNanos)
    }
}
