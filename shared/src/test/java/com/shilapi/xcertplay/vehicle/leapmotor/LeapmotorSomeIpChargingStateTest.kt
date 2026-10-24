package com.shilapi.xcertplay.vehicle.leapmotor

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LeapmotorSomeIpChargingStateTest {
    @Test fun runtimeFacadeAcceptsOnlyChargingAndKeepsOtherPreparedModulesDisconnected() {
        val state = LeapmotorSomeIpChargingState { 1000L }
        val epoch = state.beginSource()
        for (signal in LeapmotorPreparedModules.Signal.entries) {
            val value = if (signal == LeapmotorPreparedModules.Signal.CHARGE_TARGET_PERCENT) 80 else 1
            val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
            val charging = signal.module == LeapmotorPreparedModules.Module.CHARGING
            assertEquals(charging, state.receive(epoch, 0x9900, 0x9901, 0x9903, signal.event, payload, 1000L))
        }
        assertTrue(state.readings().all { it.rawValue == if (it.channel ==
            LeapmotorSomeIpChargingState.Channel.CHARGE_TARGET_PERCENT) 80 else 1 })
        state.clear()
        val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array()
        assertTrue(state.readings().all { it.rawValue == null })
        assertFalse(state.receive(epoch, 0x9900, 0x9901, 0x9903, 5702, payload, 1000L))
        assertFalse(state.receive(state.beginSource(), 0x9900, 0x9901, 0, 5702, payload, 1000L))
    }

    @Test fun chargeTargetBoundsAndOemTimeSentinelWithdrawOnlyTheirOwnField() {
        val state = LeapmotorSomeIpChargingState { 1000L }
        val epoch = state.beginSource()
        fun send(event: Int, value: Int) = state.receive(epoch, 0x9900, 0x9901, 0x9903, event,
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(), 1000L)
        fun value(event: Int) = state.readings().single { it.channel.event == event }.rawValue
        send(5702, 1)
        for (valid in listOf(50, 80, 100)) { send(11307, valid); assertEquals(valid, value(11307)) }
        for (invalid in listOf(-1, 0, 49, 101, Int.MAX_VALUE)) { send(11307, invalid); assertNull(value(11307)) }
        send(5908, 90); assertEquals(90, value(5908))
        send(5908, 0xffffff); assertNull(value(5908))
        assertEquals(1, value(5702))
        send(5908, 0); assertEquals(0, value(5908)) // Raw zero does not establish charging completion.
    }
    @Test fun acPlugLockAndOnboardChargerAreIndependentFromConnectionAndCharging() {
        var now = 1000L
        val state = LeapmotorSomeIpChargingState { now }
        val epoch = state.beginSource()
        fun send(event: Int, raw: Int) = state.receive(epoch, 0x9900, 0x9901, 0x9903, event,
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw).array(), now)
        fun reading(event: Int) = state.readings().single { it.channel.event == event }
        send(6207, 1); send(5702, 0); send(2509, 2); send(6100, 3)
        assertEquals(0, reading(5702).rawValue)
        assertEquals(1, reading(6207).rawValue)
        assertEquals(2, reading(2509).rawValue)
        assertEquals(3, reading(6100).rawValue)
        now += 60001
        send(2509, -1) // Unknown lock code never becomes unlocked/locked by default.
        assertEquals(-1, reading(2509).rawValue)
        assertEquals(LeapmotorSomeIpChargingState.Status.STALE, reading(6100).status)
        assertEquals(LeapmotorSomeIpChargingState.Status.STALE, reading(6207).status)
        state.receive(epoch, 0x9900, 0x9901, 0x9903, 2509, ByteArray(8), now)
        assertNull(reading(2509).rawValue)
    }

}
