package com.shilapi.xcertplay.vehicle.leapmotor

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class LeapmotorClimateSetErrorObservationTest {
    private fun receive(observer: LeapmotorClimateSetErrorObservation, event: Int = 1,
                        property: Int = 358614275, area: Int = 49, status: Int = 2,
                        timestamp: Long = 0, code: Int = 4) =
        observer.receive(event, property, area, status, timestamp, code)

    @Test fun checkOnlyOrAnUnarmedOwnerCannotCollectAnError() {
        val observer = LeapmotorClimateSetErrorObservation()
        assertFalse(receive(observer))
        assertNull(observer.awaitResult(0))
    }

    @Test fun onlyTheRequestedPropertyAreaAndCapturedErrorShapeCanMatch() {
        val observer = LeapmotorClimateSetErrorObservation()
        observer.begin(49)
        assertFalse(receive(observer, event = 0)) // A state-change callback is never readback here.
        assertFalse(receive(observer, property = 1))
        assertFalse(receive(observer, area = 68))
        assertFalse(receive(observer, status = 0))
        assertFalse(receive(observer, timestamp = 1))
        assertEquals(LeapmotorClimateSetErrorObservation.State.NO_MATCH_SEEN, observer.awaitResult(0)?.state)
        assertFalse("Late callbacks cannot revive a retired observation", receive(observer))
    }

    @Test fun anErrorDuringTheSetterSurvivesUntilTheObservationWindow() {
        val observer = LeapmotorClimateSetErrorObservation()
        observer.begin(49)
        assertTrue(receive(observer, code = 2))
        assertTrue(receive(observer, code = 4))
        val result = observer.awaitResult(0)!!
        assertEquals(LeapmotorClimateSetErrorObservation.State.ERROR_OBSERVED, result.state)
        assertEquals(LeapmotorClimateSetErrorObservation.Error.INVALID_ARG, result.error)
        assertSame(result, observer.awaitResult(1_000))
        assertFalse(receive(observer))
    }

    @Test fun anAsynchronousErrorWakesTheBoundedWaitWithoutAnotherWrite() {
        val observer = LeapmotorClimateSetErrorObservation()
        observer.begin(49)
        val started = CountDownLatch(1)
        val result = AtomicReference<LeapmotorClimateSetErrorObservation.Result?>()
        val worker = thread {
            started.countDown()
            result.set(observer.awaitResult(1_000))
        }
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertTrue(receive(observer, code = 3))
            worker.join(1_000)
            assertFalse(worker.isAlive)
            assertEquals(LeapmotorClimateSetErrorObservation.Error.PROPERTY_NOT_AVAILABLE, result.get()?.error)
        } finally { worker.interrupt(); worker.join(1_000) }
    }

    @Test fun wireErrorsAreWhitelistedAndUnknownCodesDoNotBecomeSuccess() {
        for ((wire, expected) in listOf(1 to LeapmotorClimateSetErrorObservation.Error.TRY_AGAIN,
                2 to LeapmotorClimateSetErrorObservation.Error.INVALID_ARG,
                3 to LeapmotorClimateSetErrorObservation.Error.PROPERTY_NOT_AVAILABLE,
                4 to LeapmotorClimateSetErrorObservation.Error.ACCESS_DENIED,
                5 to LeapmotorClimateSetErrorObservation.Error.UNKNOWN,
                Int.MIN_VALUE to LeapmotorClimateSetErrorObservation.Error.UNKNOWN)) {
            val observer = LeapmotorClimateSetErrorObservation()
            observer.begin(49)
            assertTrue(receive(observer, code = wire))
            assertEquals(expected, observer.awaitResult(0)?.error)
        }
    }

    @Test fun interruptionPreservesTheFlagAndDoesNotImplyAnAcceptedSet() {
        val observer = LeapmotorClimateSetErrorObservation()
        observer.begin(49)
        val result = AtomicReference<LeapmotorClimateSetErrorObservation.Result?>()
        val interrupted = AtomicReference(false)
        val worker = thread {
            Thread.currentThread().interrupt()
            result.set(observer.awaitResult(1_000))
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        worker.join(1_000)
        assertFalse(worker.isAlive)
        assertTrue(interrupted.get())
        assertEquals(LeapmotorClimateSetErrorObservation.State.INTERRUPTED, result.get()?.state)
    }

    @Test fun aDiagnosticOwnerAndItsWaitRemainBounded() {
        val observer = LeapmotorClimateSetErrorObservation()
        assertThrows(IllegalArgumentException::class.java) { observer.begin(0) }
        observer.begin(49)
        assertThrows(IllegalStateException::class.java) { observer.begin(68) }
        assertThrows(IllegalArgumentException::class.java) { observer.awaitResult(-1) }
        assertThrows(IllegalArgumentException::class.java) { observer.awaitResult(1_001) }
    }
}
