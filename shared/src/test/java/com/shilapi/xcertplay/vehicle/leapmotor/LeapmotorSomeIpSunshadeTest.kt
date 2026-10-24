package com.shilapi.xcertplay.vehicle.leapmotor

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LeapmotorSomeIpSunshadeTest {
    private var now = 100_000L
    private val state = LeapmotorSomeIpSunshadeState({ now })
    private var epoch = state.beginSource()
    private fun wire(raw: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw).array()
    private fun send(raw: Int, source: Long = epoch, time: Long = now) =
        state.receive(source, 0x9900, 0x9901, 0x9903, 2801, wire(raw), time)

    @Test fun rawAndInvertedOemRepresentationsAreKeptSeparate() {
        for (raw in listOf(0, 1, 25, 80, 100)) {
            val position = LeapmotorSomeIpSunshadeDecoder.decode(0x9900, 0x9901, 0x9903, 2801, wire(raw))!!
            assertEquals(raw, position.rawPosition)
            assertEquals(100 - raw, position.oemUiPosition)
        }
    }
    @Test fun wrongIdentityGlassEventMalformedAndOutOfRangeAreRejected() {
        for (event in listOf(2800, 1105, 1117, 7105))
            assertNull(LeapmotorSomeIpSunshadeDecoder.decode(0x9900, 0x9901, 0x9903, event, wire(50)))
        assertNull(LeapmotorSomeIpSunshadeDecoder.decode(0x9900, 0x9902, 0x9903, 2801, wire(50)))
        assertNull(LeapmotorSomeIpSunshadeDecoder.decode(0x9900, 0x9901, 0x9904, 2801, wire(50)))
        for (payload in listOf(null, ByteArray(0), ByteArray(3), ByteArray(8), wire(-1), wire(101)))
            assertNull(LeapmotorSomeIpSunshadeDecoder.decode(0x9900, 0x9901, 0x9903, 2801, payload))
    }
    @Test fun silenceWithdrawsBothRepresentationsWithoutRevivingOnClockRollback() {
        send(30)
        now += 60_001
        assertEquals(LeapmotorSomeIpSunshadeState.Status.STALE, state.reading().status)
        assertNull(state.reading().rawPosition)
        assertNull(state.reading().oemUiPosition)
        now -= 50_000
        assertNull(state.reading().rawPosition)
    }
    @Test fun invalidOrOutOfOrderObservationWithdrawsPreviousValue() {
        send(25); now++
        send(101)
        assertNull(state.reading().rawPosition)
        send(50); send(60, time = now - 1)
        assertNull(state.reading().rawPosition)
        send(50); send(60, time = now + 1)
        assertNull(state.reading().rawPosition)
    }
    @Test fun clearAndNewSourceRejectRetiredCallbacks() {
        send(50)
        val retired = epoch
        epoch = state.beginSource()
        assertNull(state.reading().rawPosition)
        send(70)
        assertFalse(send(30, source = retired))
        assertEquals(70, state.reading().rawPosition!!)
        state.clear()
        assertFalse(send(30, source = epoch + 1))
        assertNull(state.reading().rawPosition)
    }
    @Test fun neverStartedSourceCannotAcceptDefaultEpoch() {
        val stopped = LeapmotorSomeIpSunshadeState({ now })
        assertFalse(stopped.receive(0, 0x9900, 0x9901, 0x9903, 2801, wire(50), now))
    }
}
