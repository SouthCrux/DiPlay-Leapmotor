package com.shilapi.xcertplay.vehicle.leapmotor

import org.junit.Assert.*
import org.junit.Test

class LeapmotorClimateShellWriteProbeTest {
    private val request = LeapmotorClimateShellWriteProbe.Request(49, 20, 21)
    private class Port : LeapmotorClimateShellWriteProbe.Port {
        var allowed = true
        var reads = 0
        var closes = 0
        var failWrite = false
        var revokeOnConfig = false
        var observations = 0
        var setError: LeapmotorClimateSetErrorObservation.Result? = null
        var config = listOf(LeapmotorClimateConfiguration(358614275, 3, java.lang.Float::class.java, 1,
            listOf(LeapmotorClimateArea(49, 16f, 32f), LeapmotorClimateArea(68, 16f, 32f))))
        val writes = mutableListOf<Pair<Int, Float>>()
        override fun permitted() = allowed
        override fun configurations(): List<LeapmotorClimateConfiguration> {
            reads++
            if (revokeOnConfig) allowed = false
            return config
        }
        override fun write(areaId: Int, targetCelsius: Float) {
            writes += areaId to targetCelsius
            if (failWrite) error("private diagnostic text")
        }
        override fun close() { closes++ }
        override fun observeSetError(): LeapmotorClimateSetErrorObservation.Result? {
            observations++
            return setError
        }
    }

    private fun run(port: Port, submit: Boolean = true) =
        LeapmotorClimateShellWriteProbe.run(2000, request, submit, { 10L }) { port }

    @Test fun parsingLimitsTheExperimentToOneDegreeInsideKnownIntegerDomain() {
        assertNotNull(LeapmotorClimateShellWriteProbe.parse(arrayOf("check", "49", "20", "21")))
        assertNotNull(LeapmotorClimateShellWriteProbe.parse(arrayOf("set-once", "68", "21", "20")))
        for (args in listOf(arrayOf("set-once", "0", "20", "21"),
            arrayOf("set-once", "49", "20", "20"), arrayOf("set-once", "49", "20", "22"),
            arrayOf("set-once", "49", "17", "18"), arrayOf("set-once", "49", "32", "33"),
            arrayOf("set-once", "49", "20", "21.5"), arrayOf("auto", "49", "20", "21"))) {
            assertNull(LeapmotorClimateShellWriteProbe.parse(args))
        }
    }

    @Test fun rejectedIdentityNeverOpensAndCheckNeverWrites() {
        var opens = 0
        for (uid in listOf(0, 1000, 102000, 10123)) {
            val lines = LeapmotorClimateShellWriteProbe.run(uid, request, true, { 10L }) { opens++; Port() }
            assertEquals(2, LeapmotorClimateShellWriteProbe.exitCode(lines))
        }
        assertEquals(0, opens)
        val port = Port()
        val checked = run(port, false)
        assertTrue(port.writes.isEmpty())
        assertEquals(0, port.observations)
        assertEquals(1, port.closes)
        assertEquals(0, LeapmotorClimateShellWriteProbe.exitCode(checked))
        assertTrue(checked.any { it.contains("CHECK_ONLY|setterInvoked=false") })
    }

    @Test fun permissionBeforeAndAfterConfigBlocksWritesAndCloses() {
        val denied = Port().apply { allowed = false }
        assertEquals(2, LeapmotorClimateShellWriteProbe.exitCode(run(denied)))
        assertEquals(0, denied.reads)
        assertEquals(1, denied.closes)
        val revoked = Port().apply { revokeOnConfig = true }
        assertEquals(2, LeapmotorClimateShellWriteProbe.exitCode(run(revoked)))
        assertTrue(revoked.writes.isEmpty())
        assertEquals(1, revoked.closes)
    }

    @Test fun invalidOrAmbiguousConfigCannotSubmit() {
        val valid = Port().config.single()
        val invalid = listOf(emptyList(), listOf(valid, valid), listOf(valid.copy(access = 1)),
            listOf(valid.copy(type = java.lang.Integer::class.java)), listOf(valid.copy(changeMode = 0)),
            listOf(valid.copy(areas = listOf(LeapmotorClimateArea(49, 22f, 32f)))),
            listOf(valid.copy(areas = listOf(LeapmotorClimateArea(49, Float.NaN, 32f)))),
            listOf(valid.copy(areas = valid.areas + valid.areas.first())))
        for (configs in invalid) {
            val port = Port().apply { config = configs }
            val lines = run(port)
            assertTrue(port.writes.isEmpty())
            assertEquals(2, LeapmotorClimateShellWriteProbe.exitCode(lines))
            assertEquals(1, port.closes)
        }
    }

    @Test fun dispatchIsObservableBeforeOneSetterAndReturnNeverClaimsConfirmation() {
        val port = Port()
        val markers = mutableListOf<String>()
        val lines = LeapmotorClimateShellWriteProbe.run(2000, request, true, { 10L }, onDispatch = {
            assertTrue(port.writes.isEmpty())
            markers += it
        }) { port }
        assertEquals(1, markers.size)
        assertTrue(markers.single().contains("DISPATCH_BEGIN|area=49|target=21|submittedAtNanos=10"))
        assertEquals(listOf(49 to 21f), port.writes)
        assertTrue(lines.any { it.contains("|SUBMITTED_UNCONFIRMED|") })
        assertTrue(lines.last().contains("setterAttempted=true|closed=true|physicalResult=UNKNOWN"))
        assertEquals(0, LeapmotorClimateShellWriteProbe.exitCode(lines))
    }

    @Test fun failingSetterIsNotRetriedAndSensitiveExceptionNeverEscapes() {
        val port = Port().apply { failWrite = true }
        val lines = run(port)
        assertEquals(1, port.writes.size)
        assertEquals(1, port.closes)
        assertEquals(2, LeapmotorClimateShellWriteProbe.exitCode(lines))
        assertTrue(lines.any { it.contains("UNAVAILABLE|stage=SET") })
        assertFalse(lines.joinToString().contains("private"))
        assertFalse(lines.any { it.contains("|SUBMITTED_UNCONFIRMED|") })
    }

    @Test fun matchingAsynchronousErrorIsReportedWithoutAnotherSetterOrPhysicalClaim() {
        val port = Port().apply {
            setError = LeapmotorClimateSetErrorObservation.Result(
                LeapmotorClimateSetErrorObservation.State.ERROR_OBSERVED,
                LeapmotorClimateSetErrorObservation.Error.ACCESS_DENIED)
        }
        val lines = run(port)
        assertEquals(listOf(49 to 21f), port.writes)
        assertEquals(1, port.observations)
        assertEquals(1, port.closes)
        assertTrue(lines.any { it.contains("|SUBMITTED_UNCONFIRMED|") })
        assertTrue(lines.contains("DIPLAY_CLIMATE_SET_V1|VHAL_ERROR_OBSERVED|area=49|code=ACCESS_DENIED|physicalResult=UNKNOWN"))
        assertEquals(2, LeapmotorClimateShellWriteProbe.exitCode(lines))
    }

    @Test fun noAsynchronousMatchRemainsSubmittedUnconfirmedRatherThanAnAcknowledgement() {
        val port = Port().apply {
            setError = LeapmotorClimateSetErrorObservation.Result(LeapmotorClimateSetErrorObservation.State.NO_MATCH_SEEN)
        }
        val lines = run(port)
        assertEquals(1, port.writes.size)
        assertEquals(0, LeapmotorClimateShellWriteProbe.exitCode(lines))
        assertTrue(lines.contains("DIPLAY_CLIMATE_SET_V1|ASYNC_ERROR_WINDOW|state=NO_MATCH_SEEN|physicalResult=UNKNOWN"))
        assertFalse(lines.any { "|CONFIRMED|" in it || "|ACCEPTED|" in it })
    }

    @Test fun anErrorCanStillBeRecordedAfterAnAmbiguousSynchronousFailure() {
        val port = Port().apply {
            failWrite = true
            setError = LeapmotorClimateSetErrorObservation.Result(
                LeapmotorClimateSetErrorObservation.State.ERROR_OBSERVED,
                LeapmotorClimateSetErrorObservation.Error.TRY_AGAIN)
        }
        val lines = run(port)
        assertEquals(1, port.writes.size)
        assertEquals(1, port.observations)
        assertTrue(lines.any { "|UNAVAILABLE|stage=SET" in it })
        assertTrue(lines.any { "|VHAL_ERROR_OBSERVED|" in it })
        assertEquals(2, LeapmotorClimateShellWriteProbe.exitCode(lines))
    }

    @Test fun interruptedObservationCannotProduceAZeroDiagnosticExit() {
        val port = Port().apply {
            setError = LeapmotorClimateSetErrorObservation.Result(LeapmotorClimateSetErrorObservation.State.INTERRUPTED)
        }
        assertEquals(2, LeapmotorClimateShellWriteProbe.exitCode(run(port)))
        assertEquals(1, port.writes.size)
    }
}
