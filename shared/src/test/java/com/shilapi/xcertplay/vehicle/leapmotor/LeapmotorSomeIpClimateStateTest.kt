package com.shilapi.xcertplay.vehicle.leapmotor

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LeapmotorSomeIpClimateStateTest {
    private var now = 100_000L
    private val state = LeapmotorSomeIpClimateState({ now })
    private var epoch = state.beginSource()
    private fun wire(value: Double) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array()
    private fun send(event: Int = 11205, value: Double = 22.5, at: Long = now, source: Long = epoch) =
        state.receive(source, 0x9900, 0x9901, event, wire(value), at)
    private fun left() = state.readings().single { it.side == LeapmotorClimateSide.LEFT }
    private fun right() = state.readings().single { it.side == LeapmotorClimateSide.RIGHT }

    @Test fun decodedTargetsStayPhysicalAndHaveReceiptAgeRatherThanOriginTimestamp() {
        assertTrue(send())
        assertTrue(send(11206, 25.0))
        now += 500
        assertEquals(22.5, left().celsius!!, 0.0)
        assertEquals(25.0, right().celsius!!, 0.0)
        assertEquals(500L, left().receivedAgeMillis!!)
    }

    @Test fun silentSideExpiresIndependentlyAndNewOtherSideDoesNotReviveIt() {
        send()
        now += 59_000
        send(11206, 25.0)
        now += 1_001
        assertEquals(LeapmotorSomeIpClimateState.Status.STALE, left().status)
        assertNull(left().celsius)
        assertEquals(LeapmotorSomeIpClimateState.Status.STALE, left().status)
        assertEquals(25.0, right().celsius!!, 0.0)
    }

    @Test fun malformedValueWithdrawsOnlyItsSideAndCannotFallBackToLastGood() {
        send(); send(11206, 25.0)
        now++
        assertTrue(state.receive(epoch, 0x9900, 0x9901, 11205, byteArrayOf(0), now))
        assertNull(left().celsius)
        assertEquals(LeapmotorSomeIpClimateState.Status.UNAVAILABLE, left().status)
        assertEquals(25.0, right().celsius!!, 0.0)
        send(value = Double.NaN)
        assertNull(left().celsius)
    }

    @Test fun outOfOrderFutureAndAlreadySilentCallbacksWithdrawInsteadOfRefreshing() {
        send()
        now += 100
        send(value = 23.0)
        send(value = 20.0, at = now - 1)
        assertNull(left().celsius)
        send(value = 23.0)
        send(value = 20.0, at = now + 1)
        assertNull(left().celsius)
        send(value = 23.0)
        send(value = 20.0, at = now - 60_001)
        assertNull(left().celsius)
    }

    @Test fun retiredSourceCannotPoisonOrReviveCurrentEpoch() {
        send()
        val retired = epoch
        epoch = state.beginSource()
        assertNull(left().celsius)
        send(value = 24.0)
        assertFalse(send(value = 20.0, source = retired))
        assertEquals(24.0, left().celsius!!, 0.0)
        state.clear()
        assertFalse(send(value = 25.0))
        assertNull(left().celsius)
    }

    @Test fun unrelatedEventAndWrongIdentityDoNotKeepAReadingAlive() {
        send()
        now += 60_001
        assertFalse(send(7610))
        assertFalse(state.receive(epoch, 0x9900, 0x9902, 11205, wire(25.0), now))
        assertNull(left().celsius)
    }

    @Test fun inactiveStateRejectsEvenAnOtherwiseMatchingEpoch() {
        val inactive = LeapmotorSomeIpClimateState({ now })
        assertFalse(inactive.receive(0, 0x9900, 0x9901, 11205, wire(22.5), now))
        val current = inactive.beginSource()
        inactive.clear()
        assertFalse(inactive.receive(current + 1, 0x9900, 0x9901, 11205, wire(22.5), now))
        assertTrue(inactive.readings().all { it.celsius == null })
    }
}
