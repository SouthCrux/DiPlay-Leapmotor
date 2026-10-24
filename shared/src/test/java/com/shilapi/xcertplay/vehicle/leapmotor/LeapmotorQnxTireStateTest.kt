package com.shilapi.xcertplay.vehicle.leapmotor

import org.junit.Assert.*
import org.junit.Test

class LeapmotorQnxTireStateTest {
    private var now = 100L
    private val state = LeapmotorQnxTireState { now }
    private fun send(epoch: Long, event: Int, text: String, at: Long = now) =
        state.receive(epoch, 29185, 29186, 29184, event, text.toByteArray(), at)

    @Test fun mapsAllEightFieldsAndAcceptsNegativeTemperatureWithoutPressureSentinels() {
        val epoch = state.beginSource()
        listOf("233|0", "228|0", "229|0", "231|0", "-1|0", "18|0", "19|0", "20|0")
            .forEachIndexed { i, text -> assertTrue(send(epoch, 1109 + i, text)) }
        assertEquals(listOf(233, 228, 229, 231, -1, 18, 19, 20), state.readings().map { it.value })
        assertEquals(LeapmotorQnxTireState.Wheel.REAR_RIGHT, state.readings()[3].channel.wheel)
        assertEquals(LeapmotorQnxTireState.Quantity.TEMPERATURE_C, state.readings()[4].channel.quantity)
        assertFalse(state.receive(epoch, 39168, 39169, 39171, 1109, "200|0".toByteArray(), now))
    }

    @Test fun malformedUnknownStatusAndOutOfRangeWithdrawOnlyTheirWheel() {
        val epoch = state.beginSource()
        send(epoch, 1110, "228|0")
        listOf("233|1", "233|9", "233", "233|0|0", "NaN|0", "-1|0", "4095|0", "233|0\u0000", "２３３|0").forEach {
            send(epoch, 1109, "233|0")
            send(epoch, 1109, it)
            assertNull(it, state.readings()[0].value)
            assertEquals(228, state.readings()[1].value)
        }
    }

    @Test fun staleDelayedRetiredAndDisconnectedValuesCannotReturn() {
        val retired = state.beginSource()
        send(retired, 1109, "233|0")
        now += 30_000
        send(retired, 1110, "228|0")
        now += 30_001
        assertNull(state.readings()[0].value)
        assertEquals(228, state.readings()[1].value)
        send(retired, 1110, "200|0", 0)
        assertNull(state.readings()[1].value)
        val current = state.beginSource()
        assertFalse(send(retired, 1109, "233|0"))
        send(current, 1109, "234|0")
        state.clear()
        assertTrue(state.readings().all { it.value == null })
        assertFalse(send(current, 1109, "234|0"))
    }
}
