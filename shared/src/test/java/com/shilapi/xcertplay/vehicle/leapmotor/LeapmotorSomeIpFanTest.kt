package com.shilapi.xcertplay.vehicle.leapmotor

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LeapmotorSomeIpFanTest {
    private var now = 100_000L
    private val state = LeapmotorSomeIpFanState({ now })
    private var epoch = state.beginSource()
    private fun wire(raw: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw).array()
    private fun send(raw: Int, source: Long = epoch, time: Long = now) =
        state.receive(source, 0x9900, 0x9901, 0x9903, 1008, wire(raw), time)

    @Test fun everyOemFanLevelRoundTripsWithoutUnitConversion() {
        for (raw in 0..7) {
            val observed = LeapmotorSomeIpFanDecoder.decode(0x9900, 0x9901, 0x9903, 1008, wire(raw))!!
            assertEquals(raw, observed.level)
        }
    }
    @Test fun wrongIdentityCommandOtherStateMalformedAndOutOfRangeAreRejected() {
        for (event in listOf(1001, 1011, 1108, 7108))
            assertNull(LeapmotorSomeIpFanDecoder.decode(0x9900, 0x9901, 0x9903, event, wire(5)))
        assertNull(LeapmotorSomeIpFanDecoder.decode(0x9902, 0x9901, 0x9903, 1008, wire(5)))
        assertNull(LeapmotorSomeIpFanDecoder.decode(0x9900, 0x9902, 0x9903, 1008, wire(5)))
        assertNull(LeapmotorSomeIpFanDecoder.decode(0x9900, 0x9901, 0x9904, 1008, wire(5)))
        for (payload in listOf(null, ByteArray(0), ByteArray(3), ByteArray(8), wire(-1), wire(8)))
            assertNull(LeapmotorSomeIpFanDecoder.decode(0x9900, 0x9901, 0x9903, 1008, payload))
    }
    @Test fun silenceWithdrawsTheLevelWithoutRevivingOnClockRollback() {
        send(3)
        now += 60_001
        assertEquals(LeapmotorSomeIpFanState.Status.STALE, state.reading().status)
        assertNull(state.reading().level)
        now -= 50_000
        assertNull(state.reading().level)
    }
    @Test fun invalidOrOutOfOrderObservationWithdrawsPreviousValue() {
        send(2); now++
        send(8)
        assertNull(state.reading().level)
        send(5); send(6, time = now - 1)
        assertNull(state.reading().level)
        send(5); send(6, time = now + 1)
        assertNull(state.reading().level)
    }
    @Test fun clearAndNewSourceRejectRetiredCallbacks() {
        send(5)
        val retired = epoch
        epoch = state.beginSource()
        assertNull(state.reading().level)
        send(7)
        assertFalse(send(3, source = retired))
        assertEquals(7, state.reading().level!!)
        state.clear()
        assertFalse(send(3, source = epoch + 1))
        assertNull(state.reading().level)
    }
    @Test fun malformedAndAlreadySilentReceiptsWithdrawWithoutInferringZero() {
        send(5)
        assertTrue(state.receive(epoch, 0x9900, 0x9901, 0x9903, 1008, ByteArray(8), now))
        assertEquals(LeapmotorSomeIpFanState.Status.UNAVAILABLE, state.reading().status)
        assertNull(state.reading().level)
        send(4)
        now += 60_001
        assertTrue(send(0, time = now - 60_001))
        assertNull(state.reading().level)
        assertEquals(LeapmotorSomeIpFanState.Status.UNAVAILABLE, state.reading().status)
    }
    @Test fun neverStartedSourceCannotAcceptDefaultEpoch() {
        val stopped = LeapmotorSomeIpFanState({ now })
        assertFalse(stopped.receive(0, 0x9900, 0x9901, 0x9903, 1008, wire(5), now))
    }
}
