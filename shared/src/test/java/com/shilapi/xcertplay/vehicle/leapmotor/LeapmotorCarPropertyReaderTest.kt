package com.shilapi.xcertplay.vehicle.leapmotor

import org.junit.Assert.*
import org.junit.Test

class LeapmotorCarPropertyReaderTest {
    private var now = 100_000_000_000L
    private val backend = Backend()
    private val reader = LeapmotorCarPropertyReader(backend, 1_000_000_000L) { now }

    private inner class Backend : ReadOnlyCarPropertyAccess {
        var configs = LeapmotorEvProperty.entries.map {
            CarPropertyDescriptor(it.id, 1, true, it.type, it.changeMode)
        }
        val values = mutableMapOf(
            LeapmotorEvProperty.ENERGY_WH to raw(LeapmotorEvProperty.ENERGY_WH, 32_000f),
            LeapmotorEvProperty.NOMINAL_CAPACITY_WH to raw(LeapmotorEvProperty.NOMINAL_CAPACITY_WH, 80_000f).copy(timestampNanos = 0),
            LeapmotorEvProperty.RANGE_METERS to raw(LeapmotorEvProperty.RANGE_METERS, 123_500f),
            LeapmotorEvProperty.CHARGE_PORT_CONNECTED to raw(LeapmotorEvProperty.CHARGE_PORT_CONNECTED, false),
        )
        val reads = mutableListOf<LeapmotorEvProperty>()
        var configFailure: Exception? = null
        var failure: Exception? = null
        var afterRead: () -> Unit = {}
        override fun configurations(): List<CarPropertyDescriptor> {
            configFailure?.let { throw it }
            return configs
        }
        override fun read(property: LeapmotorEvProperty): CarPropertyReading? {
            reads += property
            failure?.let { throw it }
            return values[property].also { afterRead() }
        }
    }

    @Test fun readsOnlyVerifiedGlobalPropertiesAndConvertsMetersToKilometers() {
        val result = reader.read()
        assertEquals(32_000.0, result.currentEnergyWh!!, 0.0)
        assertEquals(80_000.0, result.nominalCapacityWh!!, 0.0)
        assertEquals(123.5, result.rangeKm!!, 0.0)
        assertEquals(false, result.chargePortConnected)
        assertEquals(LeapmotorEvProperty.entries.toList(), backend.reads)
        assertTrue(result.fields.values.all { it.state == PropertyReadState.AVAILABLE })
    }

    @Test fun permissionFilteredAbsenceDoesNotBecomeHardwareUnsupportedOrTriggerARead() {
        backend.configs = emptyList()
        assertTrue(reader.read().fields.values.all { it.state == PropertyReadState.NOT_EXPOSED })
        assertTrue(backend.reads.isEmpty())
    }

    @Test fun c11CatalogRangeOnlyCannotCreateBatteryOrChargingDataAndDenialClearsRange() {
        // Intersection with our EV allowlist from the C11 PropertyHalService dump,
        // 2026-10-16: range is global READ_WRITE / CONTINUOUS; other EV IDs absent.
        // Values remain synthetic: the firmware rejects the shell value command.
        backend.configs = listOf(CarPropertyDescriptor(
            291504904, 3, true, java.lang.Float::class.java, 2,
        ))
        val partial = reader.read()
        assertEquals(123.5, partial.rangeKm!!, 0.0)
        assertNull(partial.currentEnergyWh)
        assertNull(partial.nominalCapacityWh)
        assertNull(partial.chargePortConnected)
        for (property in LeapmotorEvProperty.entries.filter { it != LeapmotorEvProperty.RANGE_METERS }) {
            assertEquals(PropertyReadState.NOT_EXPOSED, partial.fields.getValue(property).state)
        }
        assertEquals(listOf(LeapmotorEvProperty.RANGE_METERS), backend.reads)

        backend.failure = SecurityException("read not authorized")
        val denied = reader.read()
        assertNull(denied.rangeKm)
        assertEquals(PropertyReadState.ACCESS_DENIED,
            denied.fields.getValue(LeapmotorEvProperty.RANGE_METERS).state)
        assertNull(denied.chargePortConnected)
        assertEquals(listOf(LeapmotorEvProperty.RANGE_METERS, LeapmotorEvProperty.RANGE_METERS), backend.reads)
    }

    @Test fun incompatibleOrDuplicateConfigurationIsNotRead() {
        val config = backend.configs.first()
        for (invalid in listOf(
            listOf(config.copy(access = 2)), listOf(config.copy(global = false)),
            listOf(config.copy(type = String::class.java)), listOf(config.copy(changeMode = 0)),
            listOf(config, config),
        )) {
            backend.configs = invalid
            assertEquals(PropertyReadState.INVALID_CONFIG, reader.read().fields.getValue(LeapmotorEvProperty.ENERGY_WH).state)
        }
        assertTrue(backend.reads.isEmpty())
    }

    @Test fun staticCapacityDoesNotExpireButPollingCannotRefreshAnOldDynamicTimestamp() {
        now += 1_000_000_000L
        assertNotNull(reader.read().currentEnergyWh)
        now++
        val stale = reader.read()
        assertNull(stale.currentEnergyWh)
        assertNull(stale.rangeKm)
        assertNull(stale.chargePortConnected)
        assertNotNull(stale.nominalCapacityWh)
        assertEquals(PropertyReadState.STALE, stale.fields.getValue(LeapmotorEvProperty.ENERGY_WH).state)
        assertNull(reader.read().currentEnergyWh)
    }

    @Test fun ageIsCheckedAfterAllBlockingReads() {
        backend.afterRead = { now += 400_000_000L }
        assertNull(reader.read().currentEnergyWh)
    }

    @Test fun deniedUnavailableAndBrokenSourceNeverReuseEarlierGoodValues() {
        assertNotNull(reader.read().currentEnergyWh)
        backend.failure = SecurityException("permission lost")
        assertTrue(reader.read().fields.values.all { it.state == PropertyReadState.ACCESS_DENIED })
        backend.failure = IllegalStateException("disconnected")
        assertTrue(reader.read().fields.values.all { it.state == PropertyReadState.ERROR })
        backend.failure = null
        backend.values.clear()
        assertTrue(reader.read().fields.values.all { it.state == PropertyReadState.UNAVAILABLE })
        backend.configFailure = SecurityException("configuration denied")
        assertTrue(reader.read().fields.values.all { it.state == PropertyReadState.ACCESS_DENIED })
    }

    @Test fun statusIdentityTimestampAndValueAreValidatedBeforeExposure() {
        val property = LeapmotorEvProperty.ENERGY_WH
        val good = backend.values.getValue(property)
        for (bad in listOf(
            good.copy(id = 123), good.copy(areaId = 1), good.copy(timestampNanos = now + 1),
            good.copy(timestampNanos = -1), good.copy(value = Float.NaN),
            good.copy(value = Float.POSITIVE_INFINITY), good.copy(value = -1f),
            good.copy(value = "32000"), good.copy(value = 32_000),
            good.copy(value = null), good.copy(status = 1), good.copy(status = 2),
            good.copy(status = 100), good.copy(timestampNanos = 0),
        )) {
            backend.values[property] = bad
            assertNull("Invalid reading: $bad", reader.read().currentEnergyWh)
        }
    }

    @Test fun energyAboveNominalIsRejectedAndNoFullChargeEstimateIsInvented() {
        backend.values[LeapmotorEvProperty.ENERGY_WH] = raw(LeapmotorEvProperty.ENERGY_WH, 90_000f)
        assertNull(reader.read().currentEnergyWh)
        backend.configs = backend.configs.filter { it.id != LeapmotorEvProperty.NOMINAL_CAPACITY_WH.id }
        val partial = reader.read()
        assertEquals(90_000.0, partial.currentEnergyWh!!, 0.0)
        assertNull(partial.nominalCapacityWh)
    }

    @Test fun zeroEnergyRangeAndFalsePortAreValidButZeroCapacityIsNot() {
        backend.values[LeapmotorEvProperty.ENERGY_WH] = raw(LeapmotorEvProperty.ENERGY_WH, 0f)
        backend.values[LeapmotorEvProperty.RANGE_METERS] = raw(LeapmotorEvProperty.RANGE_METERS, 0f)
        backend.values[LeapmotorEvProperty.NOMINAL_CAPACITY_WH] = raw(LeapmotorEvProperty.NOMINAL_CAPACITY_WH, 0f)
        val result = reader.read()
        assertEquals(0.0, result.currentEnergyWh!!, 0.0)
        assertEquals(0.0, result.rangeKm!!, 0.0)
        assertEquals(false, result.chargePortConnected)
        assertNull(result.nominalCapacityWh)
        backend.values[LeapmotorEvProperty.CHARGE_PORT_CONNECTED] = raw(LeapmotorEvProperty.CHARGE_PORT_CONNECTED, 1)
        assertNull(reader.read().chargePortConnected)
    }

    private fun raw(property: LeapmotorEvProperty, value: Any?) =
        CarPropertyReading(property.id, 0, 0, now, value)
}
