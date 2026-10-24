package com.shilapi.xcertplay.vehicle.leapmotor

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LeapmotorOemTelemetryStateTest {
    private var now = 1_000L
    private val state = LeapmotorOemTelemetryState({ now }, 60_000L)
    private fun soc(value: Double = 53.1) = state.receive(39168, 39169, 6000,
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array(), now)
    private fun range(value: Int = 227) = state.receive(4161, 20545, 301,
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(), now)

    @Test fun needsBothFieldsAndNeverEstimatesMissingVehicleData() {
        assertNull(state.snapshot())
        soc()
        assertNull(state.snapshot())
        range()
        val snapshot = state.snapshot()!!
        assertEquals(53.1, snapshot.batteryPercent, 0.0)
        assertEquals(227, snapshot.rangeKm)
        assertNull(snapshot.charging)
        assertNull(snapshot.rangeWarning)
        assertNull(snapshot.maxRangeKm)
        assertNull(snapshot.currentChargeWh)
        assertNull(snapshot.maxChargeWh)
    }

    @Test fun unchangedSocIsRetainedWhileRangeEventsKeepTheSessionActive() {
        soc(); range()
        repeat(10) { now += 30_000; range() }
        assertEquals(53.1, state.snapshot()!!.batteryPercent, 0.0)
        now += 60_000
        assertNotNull(state.snapshot())
        now++
        assertNull(state.snapshot())
        range()
        assertNull(state.snapshot()) // A later range cannot resurrect expired SOC.
        soc(53.0)
        assertEquals(53.0, state.snapshot()!!.batteryPercent, 0.0)
    }

    @Test fun aGapInvalidatesOldSocEvenWithoutSnapshotDuringSilence() {
        soc(); range()
        now += 60_001
        range()
        assertNull(state.snapshot())
    }

    @Test fun invalidOrMalformedDataRemovesThatFieldAndDoesNotBecomeZero() {
        for (badSoc in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 100.1)) {
            soc(); range(); soc(badSoc)
            assertNull(state.snapshot())
            range()
            assertNull(state.snapshot())
        }
        soc(); range()
        state.receive(4161, 20545, 301, byteArrayOf(1), now)
        assertNull(state.snapshot())
        range(-1)
        assertNull(state.snapshot())
        range(65_536)
        assertNull(state.snapshot())
        range(0); soc(0.0)
        assertEquals(0, state.snapshot()!!.rangeKm)
    }

    @Test fun disconnectFutureOutOfOrderAndClockRegressionClearState() {
        soc(); range(); state.clear()
        assertNull(state.snapshot())
        soc(); range()
        state.receive(4161, 20545, 301, ByteArray(4), now + 1)
        assertNull(state.snapshot())
        soc(); range()
        state.receive(4161, 20545, 301, ByteArray(4), now - 1)
        assertNull(state.snapshot())
        soc(); range(); now--
        assertNull(state.snapshot())
    }

    @Test fun unrelatedEventsCannotSupplyDataOrExtendTheSession() {
        soc(); range()
        now += 60_001
        state.receive(0, 0, 301, ByteArray(4), now)
        assertNull(state.snapshot())
    }
}
