package com.shilapi.xcertplay.vehicle.leapmotor

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LeapmotorSomeIpTireFlagsTest {
    private var now = 100_000L
    private val state = LeapmotorSomeIpTireFlagsState({ now })
    private var epoch = state.beginSource()
    private fun wire(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value).array()
    private fun send(event: Int, value: Int = 0, at: Long = now, source: Long = epoch) =
        state.receive(source, 0x9900, 0x9901, 0x9903, event, wire(value), at)
    private fun first() = state.readings().first()

    @Test fun allTwelveRoutesFollowOemWheelAndFlagMappingWithoutLearningOrResetIds() {
        val kinds = LeapmotorSomeIpTireFlagsDecoder.Kind.entries
        listOf(3301, 3306, 3311, 3316).forEachIndexed { index, base ->
            kinds.forEachIndexed { offset, kind ->
                val event = base + offset
                val channel = LeapmotorSomeIpTireFlagsDecoder.channel(event)!!
                assertEquals(LeapmotorSomeIpTireDecoder.Wheel.entries[index], channel.wheel)
                assertEquals(kind, channel.kind)
                send(event)
            }
        }
        assertTrue(state.readings().all {
            it.pressureCheck == LeapmotorSomeIpTireFlagsState.Check.ZERO_FLAGS_OBSERVED &&
                it.temperatureCheck == LeapmotorSomeIpTireFlagsState.Check.ZERO_FLAGS_OBSERVED
        })
        for (event in listOf(3300, 3304, 3305, 3309, 3310, 3314, 3315, 3319, 3320,
            3400, 3402, 3403, 22104, 2320)) {
            assertNull(LeapmotorSomeIpTireFlagsDecoder.channel(event))
        }
    }

    @Test fun missingFlagsNeverInheritOemBeanZeroDefaultsAndQuantitiesAreIndependent() {
        assertTrue(state.readings().all {
            it.pressureCheck == LeapmotorSomeIpTireFlagsState.Check.UNKNOWN &&
                it.temperatureCheck == LeapmotorSomeIpTireFlagsState.Check.UNKNOWN
        })
        send(3302)
        assertEquals(LeapmotorSomeIpTireFlagsState.Check.UNKNOWN, first().pressureCheck)
        send(3303)
        assertEquals(LeapmotorSomeIpTireFlagsState.Check.ZERO_FLAGS_OBSERVED,
            first().temperatureCheck)
        send(3301)
        assertEquals(LeapmotorSomeIpTireFlagsState.Check.ZERO_FLAGS_OBSERVED,
            first().pressureCheck)
        assertEquals(LeapmotorSomeIpTireFlagsState.Check.UNKNOWN,
            state.readings()[1].pressureCheck)
    }

    @Test fun pressureRequiresBothLeakAndPressureZeroButUnknownEnumsStayRaw() {
        send(3301); send(3302); send(3303)
        for ((event, value) in listOf(3301 to 256, 3302 to -1, 3302 to Int.MAX_VALUE)) {
            send(3301); send(3302)
            send(event, value)
            assertEquals(LeapmotorSomeIpTireFlagsState.Check.NONZERO_FLAG_OBSERVED,
                first().pressureCheck)
            assertEquals(value, first().flags.single {
                it.channel == LeapmotorSomeIpTireFlagsDecoder.channel(event)
            }.rawValue)
            assertEquals(LeapmotorSomeIpTireFlagsState.Check.ZERO_FLAGS_OBSERVED,
                first().temperatureCheck)
        }
    }

    @Test fun malformedKnownFieldWithdrawsOnlyItAndWrongIdentityCannotRefreshAnything() {
        send(3301); send(3302); send(3303)
        for (payload in listOf(null, ByteArray(0), ByteArray(3), ByteArray(5))) {
            send(3302)
            assertTrue(state.receive(epoch, 0x9900, 0x9901, 0x9903, 3302, payload, now))
            assertEquals(LeapmotorSomeIpTireFlagsState.Check.UNKNOWN, first().pressureCheck)
            assertEquals(LeapmotorSomeIpTireFlagsState.Check.ZERO_FLAGS_OBSERVED,
                first().temperatureCheck)
        }
        for ((service, instance, group) in listOf(Triple(0x9901, 0x9901, 0x9903),
            Triple(0x9900, 0x9902, 0x9903), Triple(0x9900, 0x9901, 0x9902))) {
            assertFalse(state.receive(epoch, service, instance, group, 3302, wire(0), now))
        }
        assertFalse(send(2320))
        assertEquals(LeapmotorSomeIpTireFlagsState.Check.UNKNOWN, first().pressureCheck)
    }

    @Test fun flagsExpireIndependentlyAndCacheReadsDoNotRefreshReceptionTime() {
        send(3301); send(3302)
        now += 59_000
        send(3303)
        now += 1_000
        assertEquals(LeapmotorSomeIpTireFlagsState.Check.ZERO_FLAGS_OBSERVED,
            first().pressureCheck)
        now++
        assertEquals(LeapmotorSomeIpTireFlagsState.Check.UNKNOWN, first().pressureCheck)
        assertEquals(LeapmotorSomeIpTireFlagsState.Check.ZERO_FLAGS_OBSERVED,
            first().temperatureCheck)
        assertEquals(1_001L, first().flags.last().receivedAgeMillis)
        now += 60_000
        assertEquals(LeapmotorSomeIpTireFlagsState.Check.UNKNOWN, first().temperatureCheck)
        now = 100_000
        assertTrue(first().flags.all { it.rawValue == null })
    }

    @Test fun staleFutureReorderedAndRetiredCallbacksCannotRestoreObservedZeroCheck() {
        send(3301); send(3302)
        now++
        for (at in listOf(now + 1, now - 60_001, now - 2, -1)) {
            send(3302)
            send(3302, at = at)
            assertEquals(LeapmotorSomeIpTireFlagsState.Check.UNKNOWN, first().pressureCheck)
        }
        val old = epoch
        epoch = state.beginSource()
        assertFalse(send(3301, source = old))
        assertTrue(first().flags.all { it.rawValue == null })
        send(3301); send(3302)
        state.clear()
        assertFalse(send(3302))
        assertEquals(LeapmotorSomeIpTireFlagsState.Check.UNKNOWN, first().pressureCheck)
    }
}
