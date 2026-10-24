package com.shilapi.xcertplay.vehicle.leapmotor

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LeapmotorSomeIpSubsystemStateTest {
    @Test fun allRemainingModulesUseTheirOwnFieldsAndOemBoundsWithoutEnablingOtherFacades() {
        val state = LeapmotorSomeIpSubsystemState { 1000L }
        val epoch = state.beginSource()
        val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(2).array()
        val expected = mutableMapOf<String, Int>()
        for (signal in LeapmotorPreparedModules.Signal.entries) {
            val accepted = signal.module !in setOf(LeapmotorPreparedModules.Module.CHARGING,
                LeapmotorPreparedModules.Module.DOORS_LOCKS)
            val raw = signal.allowedCodes?.first ?: 2
            val candidate = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw).array()
            assertEquals(accepted, state.receive(epoch, 0x9900, 0x9901, 0x9903, signal.event, candidate, 1000))
            if (accepted) expected[signal.name] = raw
        }
        assertTrue(state.readings().all { it.rawValue == expected[it.channel] })
        assertFalse(state.receive(epoch, 0x9900, 0x9901, 0, 2900, payload, 1000))
        assertFalse(state.receive(epoch, 0, 0x9901, 0x9903, 2900, payload, 1000))
        state.clear()
        assertFalse(state.receive(epoch, 0x9900, 0x9901, 0x9903, 2900, payload, 1000))
        assertTrue(state.readings().all { it.rawValue == null })
    }

    @Test fun mainLampFeedbackDoesNotInferAutoOrRefreshLowBeamAndRejectsWrongWire() {
        var now = 1000L
        val state = LeapmotorSomeIpSubsystemState { now }
        val epoch = state.beginSource()
        fun send(event: Int, payload: ByteArray) = state.receive(epoch, 0x9900, 0x9901, 0x9903, event, payload, now)
        fun integer(raw: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw).array()
        fun value(channel: String) = state.readings().single { it.channel == channel }
        send(3102, integer(1))
        now += 60001
        send(3813, integer(7)) // Unknown codes are observations, never an AUTO verdict.
        assertEquals(7, value("MAIN_LAMP_FEEDBACK").rawValue)
        assertEquals(LeapmotorSomeIpSubsystemState.Status.STALE, value("LOW_BEAM_STATUS").status)
        send(3813, ByteArray(8))
        assertNull(value("MAIN_LAMP_FEEDBACK").rawValue)
        val next = state.beginSource()
        assertFalse(state.receive(epoch, 0x9900, 0x9901, 0x9903, 3813, integer(1), now))
        assertTrue(state.receive(next, 0x9900, 0x9901, 0x9903, 3813, integer(-1), now))
        assertEquals(-1, value("MAIN_LAMP_FEEDBACK").rawValue)
    }

    @Test fun brakeControllerStatusIsIndependentAndCommandNamespacesCannotPopulateIt() {
        val state = LeapmotorSomeIpSubsystemState { 1000L }
        val epoch = state.beginSource()
        fun send(event: Int, payload: ByteArray) = state.receive(epoch, 0x9900, 0x9901, 0x9903, event, payload, 1000)
        fun integer(raw: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw).array()
        send(5211, integer(2))
        for (event in listOf(1600, 1620)) assertFalse(send(event, integer(1)))
        for (raw in listOf(0, 1, 3, -1, Int.MAX_VALUE)) {
            assertTrue(send(4101, integer(raw)))
            assertEquals(raw, state.readings().single { it.channel == "BRAKE_CONTROLLER_STATUS" }.rawValue)
        }
        send(4101, ByteArray(8))
        assertNull(state.readings().single { it.channel == "BRAKE_CONTROLLER_STATUS" }.rawValue)
        assertEquals(2, state.readings().single { it.channel == "GEAR" }.rawValue)
    }

    @Test fun mirrorHeatHasIndependentSidesAndIsNotAWindowPercentageOrRoofPosition() {
        var now = 1000L
        val state = LeapmotorSomeIpSubsystemState { now }
        val epoch = state.beginSource()
        fun send(event: Int, raw: Int) = state.receive(epoch, 0x9900, 0x9901, 0x9903, event,
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw).array(), now)
        fun reading(channel: String) = state.readings().single { it.channel == channel }
        send(1700, 1); send(1800, 0); send(2801, 25)
        now += 60001
        send(1800, 101) // Unknown enum, not a percentage or a Boolean verdict.
        assertEquals(101, reading("RIGHT_MIRROR_HEAT_STATUS").rawValue)
        assertEquals(LeapmotorSomeIpSubsystemState.Status.STALE, reading("LEFT_MIRROR_HEAT_STATUS").status)
        send(1700, -1)
        assertEquals(-1, reading("LEFT_MIRROR_HEAT_STATUS").rawValue)
        state.receive(epoch, 0x9900, 0x9901, 0x9903, 1700, ByteArray(8), now)
        assertNull(reading("LEFT_MIRROR_HEAT_STATUS").rawValue)
        assertEquals(101, reading("RIGHT_MIRROR_HEAT_STATUS").rawValue)
    }

    @Test fun rangeStandardsStaySeparateAndSelectorCannotKeepOldDistancesAlive() {
        var now = 1000L
        val state = LeapmotorSomeIpSubsystemState { now }
        val epoch = state.beginSource()
        fun send(event: Int, raw: Int) = state.receive(epoch, 0x9900, 0x9901, 0x9903, event,
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw).array(), now)
        fun reading(channel: String) = state.readings().single { it.channel == channel }
        send(11102, 250); send(11103, 220)
        for (selector in listOf(0, 1, 2, 3, -1, 7)) {
            send(11104, selector)
            assertEquals(selector, reading("RANGE_STANDARD").rawValue)
            assertEquals(250, reading("CLTC_RANGE").rawValue)
            assertEquals(220, reading("WLTC_RANGE").rawValue)
        }
        send(11102, -1); assertNull(reading("CLTC_RANGE").rawValue)
        send(11102, 0); assertEquals(0, reading("CLTC_RANGE").rawValue)
        now += 60001
        send(11104, 1)
        assertEquals(LeapmotorSomeIpSubsystemState.Status.STALE, reading("WLTC_RANGE").status)
        state.receive(epoch, 0x9900, 0x9901, 0x9903, 11104, ByteArray(8), now)
        assertNull(reading("RANGE_STANDARD").rawValue)
    }

    @Test fun seatPositionPercentagesHaveOwnBoundsAndCannotRefreshOtherAxes() {
        var now = 1000L
        val state = LeapmotorSomeIpSubsystemState { now }
        val epoch = state.beginSource()
        fun send(event: Int, raw: Int) = state.receive(epoch, 0x9900, 0x9901, 0x9903, event,
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw).array(), now)
        val channels = mapOf(2300 to "FRONT_LEFT_SEAT_SLIDE_POSITION", 2302 to
            "FRONT_LEFT_SEAT_BASE_FRONT_POSITION", 2306 to "FRONT_LEFT_SEAT_BACKREST_POSITION")
        fun reading(channel: String) = state.readings().single { it.channel == channel }
        send(10500, 2)
        for ((event, channel) in channels) {
            for (raw in listOf(0, 50, 100)) { send(event, raw); assertEquals(raw, reading(channel).rawValue) }
            for (raw in listOf(-1, 101, Int.MAX_VALUE)) { send(event, raw); assertNull(reading(channel).rawValue) }
            send(event, 50)
        }
        assertEquals(2, reading("DRIVER_SEAT_HEAT").rawValue)
        now += 60001
        send(2300, 25)
        assertEquals(25, reading("FRONT_LEFT_SEAT_SLIDE_POSITION").rawValue)
        assertEquals(LeapmotorSomeIpSubsystemState.Status.STALE, reading("FRONT_LEFT_SEAT_BACKREST_POSITION").status)
        assertEquals(LeapmotorSomeIpSubsystemState.Status.STALE, reading("FRONT_LEFT_SEAT_BASE_FRONT_POSITION").status)
        state.receive(epoch, 0x9900, 0x9901, 0x9903, 2300, ByteArray(8), now)
        assertNull(reading("FRONT_LEFT_SEAT_SLIDE_POSITION").rawValue)
    }

    @Test fun particulateLevelUsesOemCategoriesWithoutBecomingConcentrationOrRefreshingPurification() {
        var now = 1000L
        val state = LeapmotorSomeIpSubsystemState { now }
        val epoch = state.beginSource()
        fun send(event: Int, raw: Int) = state.receive(epoch, 0x9900, 0x9901, 0x9903, event,
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw).array(), now)
        fun reading(channel: String) = state.readings().single { it.channel == channel }
        send(1217, -1)
        for (raw in 1..6) { send(1107, raw); assertEquals(raw, reading("CABIN_PM25_LEVEL").rawValue) }
        for (raw in listOf(-1, 0, 7, Int.MAX_VALUE)) { send(1107, raw); assertNull(reading("CABIN_PM25_LEVEL").rawValue) }
        assertEquals(-1, reading("HVAC_AUTO_CLEAR_STATUS").rawValue)
        now += 60001
        send(1107, 3)
        assertEquals(LeapmotorSomeIpSubsystemState.Status.STALE, reading("HVAC_AUTO_CLEAR_STATUS").status)
        state.receive(epoch, 0x9900, 0x9901, 0x9903, 1107, ByteArray(8), now)
        assertNull(reading("CABIN_PM25_LEVEL").rawValue)
    }

    @Test fun rearClimateFieldsHaveOwnBoundsAndCannotRefreshFrontOrOtherRearFields() {
        var now = 1000L
        val state = LeapmotorSomeIpSubsystemState { now }
        val epoch = state.beginSource()
        fun send(event: Int, raw: Int) = state.receive(epoch, 0x9900, 0x9901, 0x9903, event,
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw).array(), now)
        fun reading(channel: String) = state.readings().single { it.channel == channel }
        send(1001, 1); send(9802, 9) // Unrecognized distribution remains a raw observation.
        for ((event, channel, max) in listOf(Triple(9801, "REAR_HVAC_POWER_STATUS", 1),
            Triple(9803, "REAR_HVAC_AUTO_STATUS", 1), Triple(9805, "REAR_HVAC_FAN_LEVEL", 7))) {
            for (raw in 0..max) { send(event, raw); assertEquals(raw, reading(channel).rawValue) }
            for (raw in listOf(-1, max + 1)) { send(event, raw); assertNull(reading(channel).rawValue) }
            send(event, 1)
        }
        now += 60001
        send(9805, 3)
        assertEquals(3, reading("REAR_HVAC_FAN_LEVEL").rawValue)
        assertEquals(LeapmotorSomeIpSubsystemState.Status.STALE, reading("REAR_HVAC_POWER_STATUS").status)
        assertEquals(LeapmotorSomeIpSubsystemState.Status.STALE, reading("HVAC_POWER_STATUS").status)
        state.receive(epoch, 0x9900, 0x9901, 0x9903, 9805, ByteArray(8), now)
        assertNull(reading("REAR_HVAC_FAN_LEVEL").rawValue)
    }

    @Test fun syncFeedbackIsRawVariantDependentAndCannotRefreshOtherClimateFields() {
        var now = 1000L
        val state = LeapmotorSomeIpSubsystemState { now }
        val epoch = state.beginSource()
        fun send(raw: Int) = state.receive(epoch, 0x9900, 0x9901, 0x9903, 7606,
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw).array(), now)
        fun sync() = state.readings().single { it.channel == "HVAC_SYNC_FEEDBACK" }
        for (raw in listOf(1, 2, 0, -1, 7)) { assertTrue(send(raw)); assertEquals(raw, sync().rawValue) }
        now += 60001
        state.receive(epoch, 0x9900, 0x9901, 0x9903, 1001,
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array(), now)
        assertEquals(LeapmotorSomeIpSubsystemState.Status.STALE, sync().status)
        send(1)
        state.receive(epoch, 0x9900, 0x9901, 0x9903, 7606, ByteArray(8), now)
        assertNull(sync().rawValue)
        val next = state.beginSource()
        assertFalse(state.receive(epoch, 0x9900, 0x9901, 0x9903, 7606, ByteArray(4), now))
        assertTrue(state.receive(next, 0x9900, 0x9901, 0x9903, 7606, ByteArray(4), now))
        assertEquals(0, sync().rawValue) // Raw zero is not interpreted as OFF.
    }

    @Test fun smartCleaningIsIndependentOfAutomaticCleaningAndAcMaxCodeIsPreserved() {
        var now = 1000L
        val state = LeapmotorSomeIpSubsystemState { now }
        val epoch = state.beginSource()
        fun send(event: Int, raw: Int) = state.receive(epoch, 0x9900, 0x9901, 0x9903, event,
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw).array(), now)
        fun reading(channel: String) = state.readings().single { it.channel == channel }
        send(1217, 2); send(1219, 1); send(1003, 2)
        assertEquals(2, reading("HVAC_AC_STATUS").rawValue) // OEM MAX A/C code, never clamped to Boolean.
        now += 60001
        send(1219, -1)
        assertEquals(-1, reading("HVAC_SMART_CLEAR_STATUS").rawValue)
        assertEquals(LeapmotorSomeIpSubsystemState.Status.STALE, reading("HVAC_AUTO_CLEAR_STATUS").status)
        assertEquals(LeapmotorSomeIpSubsystemState.Status.STALE, reading("HVAC_AC_STATUS").status)
        state.receive(epoch, 0x9900, 0x9901, 0x9903, 1219, ByteArray(8), now)
        assertNull(reading("HVAC_SMART_CLEAR_STATUS").rawValue)
    }

    @Test fun wiperMaintenanceAndStartNoticesStaySeparateAndNeverBecomeWipingSpeed() {
        var now = 1000L
        val state = LeapmotorSomeIpSubsystemState { now }
        val epoch = state.beginSource()
        fun send(event: Int, raw: Int) = state.receive(epoch, 0x9900, 0x9901, 0x9903, event,
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw).array(), now)
        for (event in 1521..1526) assertTrue(send(event, 1))
        assertEquals(6, state.readings().count { it.module == LeapmotorSomeIpSubsystemState.Module.WIPERS && it.rawValue == 1 })
        now += 60001
        send(1523, -1)
        assertEquals(-1, state.readings().single { it.channel == "FRONT_WIPER_START_FAILURE" }.rawValue)
        assertEquals(LeapmotorSomeIpSubsystemState.Status.STALE,
            state.readings().single { it.channel == "REAR_WIPER_START_FAILURE" }.status)
        state.receive(epoch, 0x9900, 0x9901, 0x9903, 1523, ByteArray(8), now)
        assertNull(state.readings().single { it.channel == "FRONT_WIPER_START_FAILURE" }.rawValue)
        for (unknown in listOf(3843, 3844, 3826, 3101)) assertFalse(send(unknown, 1))
    }

    @Test fun outOfBoundsSeatValuesAreWithdrawnWithoutChangingOtherSeatsOrAssumingEquipment() {
        val state = LeapmotorSomeIpSubsystemState { 1000L }
        val epoch = state.beginSource()
        fun send(event: Int, value: Int) = state.receive(epoch, 0x9900, 0x9901, 0x9903, event,
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(), 1000)
        send(10501, 2)
        for (invalid in listOf(-1, 4, Int.MAX_VALUE)) {
            send(10500, invalid)
            assertNull(state.readings().single { it.channel == "DRIVER_SEAT_HEAT" }.rawValue)
            assertEquals(2, state.readings().single { it.channel == "PASSENGER_SEAT_HEAT" }.rawValue)
        }
    }
}
