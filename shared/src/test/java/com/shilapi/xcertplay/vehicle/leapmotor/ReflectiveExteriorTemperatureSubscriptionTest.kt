package com.shilapi.xcertplay.vehicle.leapmotor

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ReflectiveExteriorTemperatureSubscriptionTest {
    interface CarPropertyEventCallback {
        fun onChangeEvent(value: Any?)
        fun onErrorEvent(property: Int, area: Int)
    }
    class Manager {
        var callback: CarPropertyEventCallback? = null
        var removed: CarPropertyEventCallback? = null
        var accepted = true
        var registrations = 0
        var removals = 0
        var registerEntered: CountDownLatch? = null
        var registerBlock: CountDownLatch? = null
        fun registerCallback(callback: CarPropertyEventCallback, property: Int, rate: Float): Boolean {
            check(property == 291505923 && rate == 1f)
            this.callback = callback; registrations++
            registerEntered?.countDown()
            registerBlock?.let { check(it.await(4, TimeUnit.SECONDS)) }
            return accepted
        }
        fun unregisterCallback(callback: CarPropertyEventCallback, property: Int) {
            check(property == 291505923)
            removed = callback; removals++
        }
    }
    @Test fun identityMethodsAreSafeAndOnlyMatchingErrorNotifiesOwner() {
        val manager = Manager()
        var errors = 0
        var changes = 0
        val subscription = ReflectiveExteriorTemperatureSubscription(manager, onFirstChange = { changes++ }) { errors++ }
        subscription.start(); subscription.start()
        val cb = manager.callback!!
        assertTrue(cb == cb)
        assertFalse(cb.equals(Any()))
        assertEquals(System.identityHashCode(cb), cb.hashCode())
        assertEquals("DiPlayExteriorTemperatureCallback", cb.toString())
        cb.onChangeEvent(Any()); cb.onChangeEvent(Any())
        assertEquals(1, changes)
        cb.onErrorEvent(123, 0)
        assertEquals(0, errors)
        cb.onErrorEvent(291505923, 0)
        assertEquals(1, errors)
        subscription.close(); subscription.close()
        cb.onChangeEvent(Any()); cb.onErrorEvent(291505923, 0)
        assertEquals(1, changes); assertEquals(1, errors)
        assertSame(cb, manager.removed)
        assertEquals(1, manager.registrations)
        assertEquals(1, manager.removals)
    }
    @Test fun closeBeforeStartCannotRegisterAndRejectedPartialRegistrationIsRemoved() {
        val manager = Manager()
        ReflectiveExteriorTemperatureSubscription(manager) {}.apply { close(); start() }
        assertEquals(0, manager.registrations)
        val failed = ReflectiveExteriorTemperatureSubscription(manager) {}
        manager.accepted = false
        try { failed.start(); fail("expected rejected registration") } catch (_: IllegalStateException) { }
        failed.close()
        assertSame(manager.callback, manager.removed)
        assertEquals(1, manager.removals)
    }
    @Test fun cleanupWaitsForInFlightRegistrationBeforeRemovingExactCallback() {
        val manager = Manager()
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
        manager.registerEntered = entered; manager.registerBlock = unblock
        val subscription = ReflectiveExteriorTemperatureSubscription(manager) {}
        val started = Thread { subscription.start() }
        val closed = AtomicBoolean()
        val cleanup = Thread { subscription.close(); closed.set(true) }
        started.start()
        assertTrue(entered.await(4, TimeUnit.SECONDS))
        try {
            cleanup.start()
            assertFalse(closed.get())
        } finally { unblock.countDown(); started.join(4_000); cleanup.join(4_000) }
        assertFalse(started.isAlive); assertFalse(cleanup.isAlive)
        assertTrue(closed.get())
        assertSame(manager.callback, manager.removed)
        assertEquals(1, manager.removals)
    }
}
