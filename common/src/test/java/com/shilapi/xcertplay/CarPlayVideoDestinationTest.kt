package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayVideoDestinationTest {
    private val passenger = CarPlayPassengerRoute("measured-firmware", 42, 7, 4)

    @Test fun normalRouteRemainsDefaultAndOnlyOneLaunchCanBeOutstanding() {
        val state = CarPlayVideoRouteState()
        assertNull(state.reserveLaunch())
        state.beginSession()
        val lease = state.reserveLaunch()!!
        assertNull(lease.passenger)
        assertNull(state.reserveLaunch())
        assertFalse(state.selectPassenger(passenger))
        assertFalse(state.selectDefault())
        state.release(lease)
        assertTrue(state.selectPassenger(passenger))
        assertEquals(passenger, state.reserveLaunch()!!.passenger)
    }

    @Test fun replacementSessionRejectsDelayedLaunchAndResetsPassengerSelection() {
        val state = CarPlayVideoRouteState()
        val oldGeneration = state.beginSession()
        state.selectPassenger(passenger)
        val old = state.reserveLaunch()!!
        val current = state.beginSession()
        assertFalse(state.isSession(oldGeneration))
        assertTrue(state.isSession(current))
        assertFalse(state.accepts(old))
        assertNull(state.findLease(old.session, old.launch))
        val fresh = state.reserveLaunch()!!
        assertNull(fresh.passenger)
        state.release(old)
        state.endSession(oldGeneration)
        assertTrue(state.accepts(fresh))
    }

    @Test fun endedOrCanceledSessionCannotAdoptAnActivityOrFallBackToMain() {
        val state = CarPlayVideoRouteState()
        val generation = state.beginSession()
        state.selectPassenger(passenger)
        val old = state.reserveLaunch()!!
        state.cancelLaunch()
        assertFalse(state.accepts(old))
        val retried = state.reserveLaunch()!!
        assertEquals(passenger, retried.passenger)
        state.endSession(generation)
        assertFalse(state.accepts(retried))
        assertNull(state.reserveLaunch())
        assertFalse(state.selectPassenger(passenger))
    }

    @Test fun receiptNumbersDoNotAllowForgedObjectToReplaceTheOwner() {
        val state = CarPlayVideoRouteState()
        state.beginSession()
        val owner = state.reserveLaunch()!!
        val fake = CarPlayVideoRouteLease(owner.session, owner.launch, passenger)
        assertFalse(state.accepts(fake))
        state.release(fake)
        assertTrue(state.accepts(owner))
        assertSame(owner, state.findLease(owner.session, owner.launch))
        assertNull(state.findLease(owner.session, owner.launch + 1))
    }

    @Test fun blockedPassengerCannotBecomeDefaultWithoutAnExplicitSelectionOrNewSession() {
        val state = CarPlayVideoRouteState()
        state.beginSession()
        state.blockPassengerDestination()
        assertNull(state.reserveLaunch())
        assertTrue(state.selectPassenger(passenger))
        val recovered = state.reserveLaunch()!!
        assertEquals(passenger, recovered.passenger)
        state.blockPassengerDestination()
        assertFalse(state.accepts(recovered))
        assertNull(state.reserveLaunch())
        assertTrue(state.selectDefault())
        assertNull(state.reserveLaunch()!!.passenger)
        state.blockPassengerDestination()
        state.beginSession()
        assertNull(state.reserveLaunch()!!.passenger)
    }
}
