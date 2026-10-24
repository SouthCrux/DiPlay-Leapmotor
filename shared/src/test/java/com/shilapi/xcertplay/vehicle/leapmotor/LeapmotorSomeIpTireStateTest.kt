package com.shilapi.xcertplay.vehicle.leapmotor

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LeapmotorSomeIpTireStateTest {
    private var now = 100_000L
    private val state = LeapmotorSomeIpTireState({ now })
    private var epoch = state.beginSource()
    private fun wire(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value).array()
    private fun send(event: Int = 3403, value: Int = 250, at: Long = now,
        source: Long = epoch) = state.receive(source, 0x9900, 0x9901, 0x9903, event, wire(value), at)
    private fun field(event: Int) = state.readings().single {
        it.channel == LeapmotorSomeIpTireDecoder.channel(event)
    }

    @Test fun wheelChannelsAndSignedIntegerFormatFollowOemRatherThanAndroidAreaIds() {
        val wheels = LeapmotorSomeIpTireDecoder.Wheel.entries
        listOf(3403, 3503, 3603, 3703).forEachIndexed { index, event ->
            assertEquals(wheels[index], LeapmotorSomeIpTireDecoder.channel(event)!!.wheel)
            assertEquals(LeapmotorSomeIpTireDecoder.Quantity.RAW_PRESSURE,
                LeapmotorSomeIpTireDecoder.channel(event)!!.quantity)
            send(event, 256 + index)
            assertEquals(256 + index, field(event).rawValue)
        }
        listOf(3404, 3504, 3604, 3704).forEachIndexed { index, event ->
            assertEquals(wheels[index], LeapmotorSomeIpTireDecoder.channel(event)!!.wheel)
            send(event, -50 + index)
            assertEquals(-50 + index, field(event).rawValue)
        }
    }

    @Test fun zeroAndMaximumPressureAndNegativeOneTemperatureAreValidRawReadings() {
        for (value in listOf(0, 4094)) {
            send(value = value)
            assertEquals(value, field(3403).rawValue)
        }
        for (value in listOf(-50, -1, 0, 150)) {
            send(3404, value)
            assertEquals(value, field(3404).rawValue)
            assertEquals(LeapmotorSomeIpTireState.Status.AVAILABLE, field(3404).status)
        }
    }

    @Test fun sentinelAndOutOfRangeWithdrawWithoutLeakingLastGoodOrOtherField() {
        send(); send(3404, -1); send(3503, 260)
        for (value in listOf(-1, 4095, Int.MAX_VALUE)) {
            send(value = value)
            assertNull(field(3403).rawValue)
        }
        assertEquals(-1, field(3404).rawValue)
        assertEquals(260, field(3503).rawValue)
        for (value in listOf(-51, 151, Int.MIN_VALUE)) {
            send(3404, value)
            assertNull(field(3404).rawValue)
        }
    }

    @Test fun exactPayloadLengthAndIdentityPreventUnknownMessagesFromTouchingCache() {
        send()
        assertFalse(state.receive(epoch, 0x9900, 0x9901, 0x9902, 3403, wire(300), now))
        assertFalse(state.receive(epoch, 0x9900, 0x9902, 0x9903, 3403, wire(300), now))
        assertFalse(state.receive(epoch, 0x9901, 0x9901, 0x9903, 3403, wire(300), now))
        assertFalse(send(22107)) // Legacy CanHeadUtils pressure ID is not this route.
        assertEquals(250, field(3403).rawValue)
        for (payload in listOf(null, byteArrayOf(), ByteArray(3), ByteArray(5))) {
            send()
            assertTrue(state.receive(epoch, 0x9900, 0x9901, 0x9903, 3403, payload, now))
            assertNull(field(3403).rawValue)
        }
    }

    @Test fun perQuantitySilenceExpiresWithoutRevivalFromAnotherWheelOrClockReset() {
        send(); send(3404, -1)
        now += 59_000
        send(3503, 270)
        now += 1_001
        assertNull(field(3403).rawValue)
        assertNull(field(3404).rawValue)
        assertEquals(LeapmotorSomeIpTireState.Status.STALE, field(3403).status)
        assertEquals(270, field(3503).rawValue)
        assertEquals(1_001L, field(3503).receivedAgeMillis)
        now = 100_000L
        assertNull(field(3403).rawValue)
    }

    @Test fun delayedFutureAndRetiredCallbacksCannotKeepOrRestoreLiveValues() {
        send()
        now += 100
        send(at = now)
        send(at = now - 1)
        assertNull(field(3403).rawValue)
        send(); send(at = now + 1)
        assertNull(field(3403).rawValue)
        send(); send(at = now - 60_001)
        assertNull(field(3403).rawValue)
        val old = epoch
        epoch = state.beginSource()
        send(value = 275)
        assertFalse(send(value = 300, source = old))
        assertEquals(275, field(3403).rawValue)
        state.clear()
        assertFalse(send())
        assertTrue(state.readings().all { it.rawValue == null })
    }
}
