package com.shilapi.xcertplay.vehicle.leapmotor

import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2VehicleStatus
import com.shilapi.xcertplay.transport.withVehicleStatusFrom
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class LeapmotorVehicleStatusProviderTest {
    private var now = 10_000L
    private val provider = LeapmotorVehicleStatusProvider(1_000L) { now }
    private val reading = LeapmotorEvReading(now, 50.0, 200, 400, false, true)

    @Test fun noVehicleIsAdvertisedUntilFreshTelemetryExists() {
        val config = Iap2IdentificationConfig(
            name = "test", modelIdentifier = "test", manufacturer = "Leapmotor",
            serialNumber = "test", firmwareVersion = "test", hardwareVersion = "test",
            carPlayUsbInterfaceNumber = 3, vehicleStatusEnabled = true,
        )
        assertFalse(config.withVehicleStatusFrom(provider).vehicleStatusEnabled)
        assertTrue(provider.publish(reading))
        assertTrue(config.withVehicleStatusFrom(provider).vehicleStatusEnabled)
        now += 1_001
        assertFalse(config.withVehicleStatusFrom(provider).vehicleStatusEnabled)
    }

    @Test fun unknownEnergyStaysAbsentInTheIap2Frame() {
        provider.publish(reading)
        val status = provider.snapshot()!!
        assertEquals(400, status.maxRangeKm)
        assertEquals(true, status.charging)
        val fields = Iap2ParameterList.parse(Iap2VehicleStatus.update(status).payload)
        for (id in 21..23) assertNull(fields.first(id))
        assertEquals(50_000, ByteBuffer.wrap(fields.first(24)!!.payload).int)
        assertEquals(200, ByteBuffer.wrap(fields.first(11)!!.payload).short.toInt())
    }

    @Test fun measuredEnergyIsNotEstimatedFromSoc() {
        provider.publish(reading.copy(currentChargeWh = 30_000L, maxChargeWh = 65_000L))
        assertEquals(30_000L, provider.snapshot()!!.currentChargeWh)
        assertEquals(65_000L, provider.snapshot()!!.maxChargeWh)
    }

    @Test fun staleFutureAndOutOfOrderReadingsClearTheCache() {
        val invalid = listOf(
            reading.copy(observedAtMillis = now - 1_001),
            reading.copy(observedAtMillis = now + 1),
            reading.copy(observedAtMillis = now - 1),
        )
        for (candidate in invalid) {
            assertTrue(provider.publish(reading))
            assertFalse(provider.publish(candidate))
            assertNull(provider.snapshot())
        }
    }

    @Test fun invalidValuesAreRejectedInsteadOfClampedOrSent() {
        val invalid = listOf(
            reading.copy(batteryPercent = Double.NaN),
            reading.copy(batteryPercent = Double.POSITIVE_INFINITY),
            reading.copy(batteryPercent = -1.0), reading.copy(batteryPercent = 101.0),
            reading.copy(rangeKm = -1), reading.copy(rangeKm = 65_536),
            reading.copy(maxRangeKm = 199), reading.copy(maxRangeKm = 65_536),
            reading.copy(currentChargeWh = -1), reading.copy(maxChargeWh = 0),
            reading.copy(currentChargeWh = 0x1_0000_0000L),
            reading.copy(maxChargeWh = 0x1_0000_0000L),
            reading.copy(currentChargeWh = 20, maxChargeWh = 10),
        )
        for (candidate in invalid) {
            provider.publish(reading)
            assertFalse(provider.publish(candidate))
            assertNull(provider.snapshot())
        }
    }

    @Test fun lossOfSourceAndClockRegressionInvalidateReadings() {
        provider.publish(reading)
        provider.clear()
        assertNull(provider.snapshot())
        provider.publish(reading)
        now--
        assertNull(provider.snapshot())
        now++
        assertNull(provider.snapshot())
    }

    @Test fun zeroChargeAndTheFreshnessBoundaryAreValid() {
        assertTrue(provider.publish(reading.copy(batteryPercent = 0.0, rangeKm = 0, currentChargeWh = 0)))
        now += 1_000
        assertEquals(0.0, provider.snapshot()!!.batteryPercent, 0.0)
        now++
        assertNull(provider.snapshot())
    }
}
