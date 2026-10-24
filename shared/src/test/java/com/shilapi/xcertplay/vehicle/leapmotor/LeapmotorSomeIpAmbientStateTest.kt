package com.shilapi.xcertplay.vehicle.leapmotor

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LeapmotorSomeIpAmbientStateTest {
    private var now = 100_000L
    private val state = LeapmotorSomeIpAmbientState({ now })
    private var epoch = state.beginSource()
    private fun wire(value: Double) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array()
    private fun send(event: Int = 1221, value: Double = 23.125, at: Long = now,
        source: Long = epoch) = state.receive(source, 0x9900, 0x9901, 0x9903, event, wire(value), at)
    private fun field(event: Int) = state.readings().single {
        it.channel == LeapmotorSomeIpAmbientDecoder.channel(event)
    }

    @Test fun cabinAndCorrectedExteriorAreUnverifiedObservationsNotSetpointsOrDegrees() {
        send(); send(1016, -1.25)
        assertEquals(23.125, field(1221).rawValue!!, 0.0)
        assertEquals(-1.25, field(1016).rawValue!!, 0.0)
        assertEquals(LeapmotorSomeIpAmbientState.Status.OBSERVED_UNVERIFIED, field(1221).status)
        assertFalse(send(11205, 20.0))
        assertFalse(send(11202, 20.0)) // Named uncorrected exterior has no verified subscription here.
        send(value = 0.0)
        assertEquals(LeapmotorSomeIpAmbientState.Status.OBSERVED_UNVERIFIED, field(1221).status)
        // No physical plausibility range is fabricated. Even finite extreme values remain unverified.
        send(value = Double.MAX_VALUE)
        assertEquals(Double.MAX_VALUE, field(1221).rawValue!!, 0.0)
    }

    @Test fun malformedAndNonfiniteWithdrawOnlyTheirFieldRatherThanKeepLastValue() {
        send(); send(1016, 12.0)
        for (payload in listOf(null, ByteArray(7), ByteArray(9), wire(Double.NaN),
            wire(Double.POSITIVE_INFINITY), wire(Double.NEGATIVE_INFINITY))) {
            send()
            assertTrue(state.receive(epoch, 0x9900, 0x9901, 0x9903, 1221, payload, now))
            assertNull(field(1221).rawValue)
            assertEquals(12.0, field(1016).rawValue!!, 0.0)
        }
    }

    @Test fun wrongTupleCannotRefreshSilentChannelAndOtherLocationExpiresIndependently() {
        send()
        assertFalse(state.receive(epoch, 0x9900, 0x9901, 0x9902, 1221, wire(30.0), now))
        assertFalse(state.receive(epoch, 0x9900, 0x9902, 0x9903, 1221, wire(30.0), now))
        assertFalse(state.receive(epoch, 0x9901, 0x9901, 0x9903, 1221, wire(30.0), now))
        now += 59_000
        send(1016, 11.0)
        now += 1_001
        assertEquals(LeapmotorSomeIpAmbientState.Status.STALE, field(1221).status)
        assertNull(field(1221).rawValue)
        assertEquals(11.0, field(1016).rawValue!!, 0.0)
        assertEquals(1_001L, field(1016).receivedAgeMillis)
        now = 100_000L
        assertNull(field(1221).rawValue)
    }

    @Test fun delayedFutureAndRetiredSourceMessagesWithdrawOrAreIgnored() {
        send(); now += 100; send()
        send(at = now - 1); assertNull(field(1221).rawValue)
        send(); send(at = now + 1); assertNull(field(1221).rawValue)
        send(); send(at = now - 60_001); assertNull(field(1221).rawValue)
        val retired = epoch
        epoch = state.beginSource()
        send(value = 25.0)
        assertFalse(send(value = 30.0, source = retired))
        assertEquals(25.0, field(1221).rawValue!!, 0.0)
        state.clear()
        assertFalse(send())
        assertTrue(state.readings().all { it.rawValue == null })
    }
}
