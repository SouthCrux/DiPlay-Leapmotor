package com.shilapi.xcertplay.vehicle.leapmotor

import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorPreparedModules.Module
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorPreparedModules.Signal
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorPreparedModuleState.Status
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LeapmotorPreparedModulesTest {
    private var now = 1000L
    private val state = LeapmotorPreparedModuleState({ now })
    private fun int(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun receive(epoch: Long, signal: Signal, value: Int, at: Long = now) = state.receive(
        epoch, 0x9900, 0x9901, 0x9903, signal.event, int(value), at)
    private fun reading(signal: Signal) = state.readings(signal.module).single { it.signal == signal }

    @Test fun syntheticReplayRoutesEachFieldWithoutPromotingItToVerifiedData() {
        val epoch = state.beginSource()
        val fixture = checkNotNull(javaClass.getResourceAsStream("/c11-prepared-modules-simulated.tsv"))
            .bufferedReader().use { it.readLines() }
        fixture.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val columns = line.split(Regex("\\s+"))
            val (module, signalName, event, at, hex) = columns
            val raw = columns[5]
            val signal = Signal.valueOf(signalName)
            val payload = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            assertTrue(state.receive(epoch, 0x9900, 0x9901, 0x9903, event.toInt(), payload, at.toLong()))
            val reading = state.readings(Module.valueOf(module)).single { it.signal == signal }
            assertEquals(Status.OBSERVED_UNVERIFIED, reading.status)
            assertEquals(raw.toInt(), reading.rawValue)
            assertEquals(0L, reading.receivedAgeMillis)
        }
    }

    @Test fun unrelatedSourcesAndIdentifierNamespacesCannotPopulateTheCache() {
        val epoch = state.beginSource()
        for (identity in listOf(intArrayOf(0x9901, 0x9901, 0x9903),
            intArrayOf(0x9900, 0x9900, 0x9903), intArrayOf(0x9900, 0x9901, 0x9900))) {
            assertFalse(state.receive(epoch, identity[0], identity[1], identity[2], 5702, int(1), now))
        }
        // SDK/broker names do not make their IDs SOME/IP vehicle events.
        assertFalse(state.receive(epoch, 0x9900, 0x9901, 0x9903, 17, int(1), now))
        assertTrue(state.readings(Module.CHARGING).all { it.rawValue == null })
        assertNull(LeapmotorPreparedModules.decode(0x9900, 0x9901, 0x9903, 6000, int(1)))
    }

    @Test fun malformedKnownFieldWithdrawsOnlyThatField() {
        val epoch = state.beginSource()
        receive(epoch, Signal.CHARGE_STATUS, 1)
        receive(epoch, Signal.AC_CONNECTION, 0)
        for (payload in listOf(null, byteArrayOf(), ByteArray(3), ByteArray(8))) {
            assertTrue(state.receive(epoch, 0x9900, 0x9901, 0x9903, 5702, payload, now))
            assertEquals(Status.UNAVAILABLE, reading(Signal.CHARGE_STATUS).status)
            assertNull(reading(Signal.CHARGE_STATUS).rawValue)
            assertEquals(0, reading(Signal.AC_CONNECTION).rawValue)
        }
    }

    @Test fun positionsHaveOemBoundsButNoAssumedOpenDirectionOrBooleanEnum() {
        val epoch = state.beginSource()
        for (signal in Signal.entries.filter { it.position }) {
            for (valid in listOf(0, 50, 100)) {
                receive(epoch, signal, valid)
                assertEquals(valid, reading(signal).rawValue)
            }
            for (invalid in listOf(-1, 101, Int.MAX_VALUE)) {
                receive(epoch, signal, invalid)
                assertNull(reading(signal).rawValue)
            }
        }
        receive(epoch, Signal.CHARGE_STATUS, -1)
        receive(epoch, Signal.GEAR, 255)
        assertEquals(-1, reading(Signal.CHARGE_STATUS).rawValue)
        assertEquals(Status.OBSERVED_UNVERIFIED, reading(Signal.GEAR).status)
    }

    @Test fun heartbeatFromOtherFieldsCannotKeepOldChargeOrDoorDataAlive() {
        val epoch = state.beginSource()
        receive(epoch, Signal.CHARGE_STATUS, 1)
        receive(epoch, Signal.DRIVER_DOOR, 1)
        now = 61000
        assertEquals(1, reading(Signal.CHARGE_STATUS).rawValue)
        now++
        receive(epoch, Signal.GEAR, 0)
        assertEquals(Status.STALE, reading(Signal.CHARGE_STATUS).status)
        assertNull(reading(Signal.CHARGE_STATUS).rawValue)
        assertEquals(Status.STALE, reading(Signal.DRIVER_DOOR).status)
        assertEquals(0, reading(Signal.GEAR).rawValue)
    }

    @Test fun sourceLossAndNewEpochNeverRevivePreviouslyReceivedFields() {
        val first = state.beginSource()
        receive(first, Signal.AC_CONNECTION, 1)
        state.clear()
        assertFalse(receive(first, Signal.AC_CONNECTION, 1))
        val second = state.beginSource()
        assertFalse(receive(first, Signal.AC_CONNECTION, 1))
        receive(second, Signal.DC_CONNECTION, 0)
        assertNull(reading(Signal.AC_CONNECTION).rawValue)
        assertEquals(0, reading(Signal.DC_CONNECTION).rawValue)
    }

    @Test fun outOfOrderFutureAndOldCallbacksWithdrawTheAffectedField() {
        val epoch = state.beginSource()
        receive(epoch, Signal.CHARGE_STATUS, 1)
        for (at in listOf(-1L, 999L, 1001L)) {
            assertTrue(receive(epoch, Signal.CHARGE_STATUS, 0, at))
            assertNull(reading(Signal.CHARGE_STATUS).rawValue)
        }
        now = 62000
        receive(epoch, Signal.CHARGE_STATUS, 0, 1000)
        assertNull(reading(Signal.CHARGE_STATUS).rawValue)
        receive(epoch, Signal.CHARGE_STATUS, 1)
        assertEquals(1, reading(Signal.CHARGE_STATUS).rawValue)
        now = 61999
        assertNull(reading(Signal.CHARGE_STATUS).rawValue)
    }

    @Test fun unobservedModulesRemainUnavailableAndClimateIsDeferred() {
        val epoch = state.beginSource()
        receive(epoch, Signal.CHARGE_STATUS, 1)
        assertTrue(state.readings(Module.ENERGY).isNotEmpty()) // CLTC/WLTC use integer feedback.
        for (module in listOf(Module.ENERGY, Module.SEATS, Module.CLIMATE)) {
            assertTrue(state.readings(module).all { it.rawValue == null && it.status == Status.UNAVAILABLE })
        }
        assertEquals(Module.CLIMATE, Module.entries.last())
    }
}
