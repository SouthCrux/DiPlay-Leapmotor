package com.shilapi.xcertplay.vehicle.leapmotor

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LeapmotorSomeIpMeasurementsStateTest {
    private var now = 1000L
    private val state = LeapmotorSomeIpMeasurementsState({ now })
    private fun double(value: Double) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array()
    private fun reading(event: Int) = state.readings().single { it.channel.event == event }
    private fun send(epoch: Long, event: Int, value: Double, at: Long = now): Boolean {
        val r = LeapmotorSomeIpMeasurementsState.Channel.entries.single { it.event == event }.route
        return state.receive(epoch, r.service, r.instance, r.group, event, double(value), at)
    }

    @Test fun syntheticTripReplaySeparatesDistanceAndEnergyAndDoesNotTreatRangeAsEnergy() {
        val epoch = state.beginSource()
        // Synthetic values, not a recording or a unit validation.
        val vectors = mapOf(12289 to 12.5, 12291 to 2.125, 12295 to 45.75,
            12297 to 8.5, 12307 to 100.0, 12309 to 20.25, 4030 to 0.0)
        vectors.forEach { (event, value) -> assertTrue(send(epoch, event, value)) }
        vectors.forEach { (event, value) ->
            assertEquals(value, reading(event).rawValue)
            assertEquals(LeapmotorSomeIpMeasurementsState.Status.OBSERVED_UNVERIFIED, reading(event).status)
        }
        assertFalse(state.receive(epoch, 4161, 20545, 17920, 301, double(227.0), now))
        assertFalse(state.receive(epoch, 0x9900, 0x9901, 0x9903, 12291, double(1.0), now))
        assertFalse(state.receive(epoch, 4161, 20545, 0x9903, 12291, double(1.0), now))
    }

    @Test fun tripDurationsUseIntegerMinutesAndRejectNegativeOrWrongWireValues() {
        val epoch = state.beginSource()
        send(epoch, 12291, 12.5)
        for (event in listOf(12290, 12296, 12308)) {
            fun receive(payload: ByteArray) = state.receive(epoch, 4161, 20545, 17920, event, payload, now)
            assertTrue(receive(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(60).array()))
            assertEquals(60.0, reading(event).rawValue)
            assertEquals(LeapmotorSomeIpMeasurementsState.OemUnit.MINUTES, reading(event).channel.oemUnit)
            receive(double(60.0)); assertNull(reading(event).rawValue)
            receive(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(-1).array())
            assertNull(reading(event).rawValue)
            assertEquals(12.5, reading(12291).rawValue)
        }
    }

    @Test fun rearDisplayedTemperatureUsesUnscaledDoubleAndWithdrawsInvalidValues() {
        val epoch = state.beginSource()
        send(epoch, 12289, 12.5)
        for (raw in listOf(18.0, 20.5, 32.0)) {
            send(epoch, 9800, raw)
            assertEquals(raw, reading(9800).rawValue)
            assertEquals(LeapmotorSomeIpMeasurementsState.OemUnit.CELSIUS, reading(9800).channel.oemUnit)
        }
        for (raw in listOf(17.9, 32.1, 41.0, -1.0, Double.NaN)) {
            send(epoch, 9800, raw); assertNull(reading(9800).rawValue)
            assertEquals(12.5, reading(12289).rawValue)
        }
        send(epoch, 9800, 20.5)
        state.receive(epoch, 0x9900, 0x9901, 0x9903, 9800, ByteArray(4), now)
        assertNull(reading(9800).rawValue)
        send(epoch, 9800, 20.5)
        now += 60001
        send(epoch, 4030, 0.0)
        assertEquals(LeapmotorSomeIpMeasurementsState.Status.STALE, reading(9800).status)
        val next = state.beginSource()
        assertFalse(send(epoch, 9800, 22.0))
        assertTrue(send(next, 9800, 22.0))
    }

    @Test fun malformedAndNonfiniteMeasurementsWithdrawOnlyTheirOwnField() {
        val epoch = state.beginSource()
        send(epoch, 12289, 12.5)
        for (payload in listOf(null, ByteArray(4), ByteArray(9), double(Double.NaN), double(Double.POSITIVE_INFINITY))) {
            send(epoch, 12291, 2.0)
            assertTrue(state.receive(epoch, 4161, 20545, 17920, 12291, payload, now))
            assertNull(reading(12291).rawValue)
            assertEquals(12.5, reading(12289).rawValue)
        }
        send(epoch, 12291, -1.0) // Unknown finite sentinels stay raw, never a valid energy claim.
        assertEquals(-1.0, reading(12291).rawValue)
    }

    @Test fun speedHeartbeatsCannotKeepOldEnergyAliveAndRetiredSourcesCannotReviveIt() {
        val epoch = state.beginSource()
        send(epoch, 12291, 3.0)
        now += 60001
        send(epoch, 4030, 40.0)
        assertNull(reading(12291).rawValue)
        assertEquals(LeapmotorSomeIpMeasurementsState.Status.STALE, reading(12291).status)
        val next = state.beginSource()
        assertFalse(send(epoch, 12291, 5.0))
        send(next, 12289, 10.0)
        assertNull(reading(12291).rawValue)
        state.clear()
        assertTrue(state.readings().all { it.rawValue == null })
    }

    @Test fun invalidReceptionTimesAndClockRollbackNeverExposeOldMeasurements() {
        val epoch = state.beginSource()
        send(epoch, 12291, 2.0)
        for (at in listOf(-1L, 999L, 1001L)) {
            send(epoch, 12291, 3.0, at)
            assertNull(reading(12291).rawValue)
        }
        now = 62000
        send(epoch, 12291, 3.0, 1000)
        assertNull(reading(12291).rawValue)
        send(epoch, 12291, 4.0)
        now--
        assertNull(reading(12291).rawValue)
    }
}
