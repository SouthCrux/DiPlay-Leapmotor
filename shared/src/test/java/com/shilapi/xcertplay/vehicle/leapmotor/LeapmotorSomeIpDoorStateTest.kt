package com.shilapi.xcertplay.vehicle.leapmotor

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LeapmotorSomeIpDoorStateTest {
    @Test fun runtimeFacadeAcceptsOnlyDoorEventsAndRejectsRetiredOrUnrelatedSources() {
        val state = LeapmotorSomeIpDoorState { 1000L }
        val epoch = state.beginSource()
        val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(-1).array()
        for (signal in LeapmotorPreparedModules.Signal.entries) {
            val candidate = if (signal == LeapmotorPreparedModules.Signal.HOOD_STATUS || signal.position)
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array() else payload
            assertEquals(signal.module == LeapmotorPreparedModules.Module.DOORS_LOCKS,
                state.receive(epoch, 0x9900, 0x9901, 0x9903, signal.event, candidate, 1000L))
        }
        assertTrue(state.readings().all { it.rawValue == (if (it.channel ==
            LeapmotorSomeIpDoorState.Channel.HOOD_STATUS || it.channel ==
            LeapmotorSomeIpDoorState.Channel.TAILGATE_POSITION) 1 else -1) &&
            it.status == LeapmotorSomeIpDoorState.Status.OBSERVED_UNVERIFIED })
        assertFalse(state.receive(epoch, 0x9900, 0x9901, 0, 3860, payload, 1000L))
        assertFalse(state.receive(epoch, 0x9900, 0, 0x9903, 3860, payload, 1000L))
        assertFalse(state.receive(epoch, 0, 0x9901, 0x9903, 3860, payload, 1000L))
        state.clear()
        assertTrue(state.readings().all { it.rawValue == null })
        assertFalse(state.receive(epoch, 0x9900, 0x9901, 0x9903, 3860, payload, 1000L))
    }
    @Test fun poweredTailgatePositionAndStatusNeverOverwriteTheBodyDoorSensor() {
        var now = 1000L
        val state = LeapmotorSomeIpDoorState { now }
        val epoch = state.beginSource()
        fun send(event: Int, raw: Int) = state.receive(epoch, 0x9900, 0x9901, 0x9903, event,
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw).array(), now)
        fun reading(channel: LeapmotorSomeIpDoorState.Channel) = state.readings().single { it.channel == channel }
        val position = LeapmotorSomeIpDoorState.Channel.TAILGATE_POSITION
        val powered = LeapmotorSomeIpDoorState.Channel.POWER_TAILGATE_STATUS
        val body = LeapmotorSomeIpDoorState.Channel.TAILGATE_STATUS
        send(3823, 2); send(802, 4)
        for (raw in listOf(0, 50, 100)) { send(815, raw); assertEquals(raw, reading(position).rawValue) }
        for (raw in listOf(-1, 101, Int.MAX_VALUE)) { send(815, raw); assertNull(reading(position).rawValue) }
        assertEquals(4, reading(powered).rawValue); assertEquals(2, reading(body).rawValue)
        now += 60001
        send(815, 25)
        assertEquals(25, reading(position).rawValue)
        assertEquals(LeapmotorSomeIpDoorState.Status.STALE, reading(body).status)
        assertEquals(LeapmotorSomeIpDoorState.Status.STALE, reading(powered).status)
        send(802, -1) // Unrecognized code stays raw; it is not a motion verdict.
        assertEquals(-1, reading(powered).rawValue)
        state.receive(epoch, 0x9900, 0x9901, 0x9903, 802, ByteArray(8), now)
        assertNull(reading(powered).rawValue)
        assertEquals(25, reading(position).rawValue)
    }

}
