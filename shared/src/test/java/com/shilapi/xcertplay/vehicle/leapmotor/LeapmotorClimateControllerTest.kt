package com.shilapi.xcertplay.vehicle.leapmotor

import org.junit.Assert.*
import org.junit.Test

class LeapmotorClimateControllerTest {
    private var now = 100_000_000_000L
    private val backend = Backend()
    private val left = LeapmotorClimateSide.LEFT

    private inner class Backend : LeapmotorClimateAccess {
        var config = listOf(LeapmotorClimateConfiguration(
            358614275, 3, java.lang.Float::class.java, 1,
            listOf(LeapmotorClimateArea(49, 16f, 32f), LeapmotorClimateArea(68, 16f, 32f)),
        ))
        var raw = CarPropertyReading(358614275, 49, 0, now - 1, 22f)
        val writes = mutableListOf<Pair<Int, Float>>()
        var configFailure: Exception? = null
        var readFailure: Exception? = null
        var writeFailure: Exception? = null
        override fun configurations(): List<LeapmotorClimateConfiguration> {
            configFailure?.let { throw it }
            return config
        }
        override fun readTarget(areaId: Int): CarPropertyReading {
            readFailure?.let { throw it }
            return raw
        }
        override fun writeTarget(areaId: Int, celsius: Float) {
            writeFailure?.let { throw it }
            writes += areaId to celsius
        }
    }

    private fun controller(enabled: Boolean = true, steps: Boolean = true) = LeapmotorClimateController(
        backend, enabled, if (steps) mapOf(left to 0.5) else emptyMap(), elapsedNanos = { now },
    )

    @Test fun defaultIsReadOnlyAndCapturedHalRangeDoesNotInventAnIncrement() {
        val disabled = LeapmotorClimateController(backend, elapsedNanos = { now })
        val reading = disabled.observe(left)
        assertEquals(22.0, reading.targetCelsius!!, 0.0)
        assertEquals(16.0, reading.minCelsius!!, 0.0)
        assertNull(reading.verifiedStepCelsius)
        assertFalse(reading.commandsEnabled)
        assertEquals(LeapmotorClimateCommandState.DISABLED, disabled.submitTarget(left, 23.0).state)
        assertEquals(LeapmotorClimateCommandState.NOT_READY, controller(steps = false).submitTarget(left, 23.0).state)
        assertTrue(backend.writes.isEmpty())
    }

    @Test fun setterReturnIsUnconfirmedAndOldObservationCannotAcknowledgeIt() {
        val controller = controller()
        val submitted = controller.submitTarget(left, 23.5)
        assertEquals(listOf(49 to 23.5f), backend.writes)
        assertEquals(LeapmotorClimateCommandState.SUBMITTED_UNCONFIRMED, submitted.state)
        backend.raw = backend.raw.copy(value = 23.5f)
        assertEquals(LeapmotorClimateCommandState.SUBMITTED_UNCONFIRMED,
            controller.confirmTarget(submitted.receipt!!).state)
        now += 100
        backend.raw = backend.raw.copy(timestampNanos = now)
        assertEquals(LeapmotorClimateCommandState.CONFIRMED, controller.confirmTarget(submitted.receipt).state)
        assertEquals(LeapmotorClimateCommandState.SUPERSEDED, controller.confirmTarget(submitted.receipt).state)
    }

    @Test fun changedOrCanceledIntentCannotBeConfirmedByLateReadback() {
        val controller = controller()
        val first = controller.submitTarget(left, 23.0).receipt!!
        controller.submitTarget(left, 24.0)
        assertEquals(LeapmotorClimateCommandState.SUPERSEDED, controller.confirmTarget(first).state)
        val second = controller.submitTarget(left, 23.0).receipt!!
        controller.cancelPending()
        assertEquals(LeapmotorClimateCommandState.SUPERSEDED, controller.confirmTarget(second).state)
        assertEquals(LeapmotorClimateCommandState.SUPERSEDED, controller().confirmTarget(second).state)
    }

    @Test fun invalidTargetsNeverReachSetterOrRoundToDifferentIntent() {
        val controller = controller()
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, 15.5, 32.5, 22.25)) {
            assertEquals(LeapmotorClimateCommandState.INVALID_TARGET, controller.submitTarget(left, value).state)
        }
        assertTrue(backend.writes.isEmpty())
    }

    @Test fun absentOrMalformedConfigurationDoesNotBecomeAvailable() {
        val valid = backend.config.single()
        for (invalid in listOf(
            emptyList(), listOf(valid, valid), listOf(valid.copy(type = String::class.java)),
            listOf(valid.copy(access = 2)), listOf(valid.copy(changeMode = 0)),
            listOf(valid.copy(areas = emptyList())),
            listOf(valid.copy(areas = listOf(LeapmotorClimateArea(49, 32f, 16f)))),
            listOf(valid.copy(areas = listOf(LeapmotorClimateArea(49, Float.NaN, 32f)))),
            listOf(valid.copy(areas = listOf(LeapmotorClimateArea(49, 16f, 32.1f)))),
        )) {
            backend.config = invalid
            val controller = controller()
            assertNull(controller.observe(left).targetCelsius)
            assertEquals(LeapmotorClimateCommandState.NOT_READY, controller.submitTarget(left, 23.0).state)
        }
        assertTrue(backend.writes.isEmpty())
    }

    @Test fun wrongSideIdentityStatusTypeOrTimeNeverLeaksLastGoodValue() {
        val controller = controller()
        val valid = backend.raw
        assertNotNull(controller.observe(left).targetCelsius)
        for (invalid in listOf(
            valid.copy(id = 0), valid.copy(areaId = 68), valid.copy(status = 1), valid.copy(status = 2),
            valid.copy(timestampNanos = 0), valid.copy(timestampNanos = now + 1),
            valid.copy(timestampNanos = now - 5_000_000_001), valid.copy(value = 22.0),
            valid.copy(value = Float.NaN), valid.copy(value = 33f), valid.copy(value = 22.25f),
        )) {
            backend.raw = invalid
            assertNull(controller.observe(left).targetCelsius)
            assertEquals(LeapmotorClimateCommandState.NOT_READY, controller.submitTarget(left, 23.0).state)
        }
        backend.raw = valid
        assertEquals(LeapmotorClimateState.INVALID_VALUE, controller.observe(LeapmotorClimateSide.RIGHT).state)
        assertTrue(backend.writes.isEmpty())
    }

    @Test fun accessLossAndSetterFailureAreNotSuccessfulCommands() {
        val controller = controller()
        backend.readFailure = SecurityException()
        assertEquals(LeapmotorClimateCommandState.ACCESS_DENIED, controller.submitTarget(left, 23.0).state)
        backend.readFailure = null
        backend.writeFailure = SecurityException()
        assertEquals(LeapmotorClimateCommandState.ACCESS_DENIED, controller.submitTarget(left, 23.0).state)
        backend.writeFailure = IllegalStateException()
        assertEquals(LeapmotorClimateCommandState.ERROR, controller.submitTarget(left, 23.0).state)
        backend.writeFailure = null
        val receipt = controller.submitTarget(left, 23.0).receipt!!
        backend.configFailure = SecurityException()
        assertEquals(LeapmotorClimateCommandState.ACCESS_DENIED, controller.confirmTarget(receipt).state)
        backend.configFailure = null
        assertEquals(LeapmotorClimateCommandState.SUPERSEDED, controller.confirmTarget(receipt).state)
    }

    @Test fun readOnlyOrStaleSourceDoesNotAcquireWriteCapability() {
        val controller = controller()
        backend.config = listOf(backend.config.single().copy(access = 1))
        assertNotNull(controller.observe(left).targetCelsius)
        assertFalse(controller.observe(left).commandsEnabled)
        assertEquals(LeapmotorClimateCommandState.NOT_READY, controller.submitTarget(left, 23.0).state)
        now += 6_000_000_000
        assertEquals(LeapmotorClimateState.STALE, controller.observe(left).state)
        assertTrue(backend.writes.isEmpty())
    }
}
