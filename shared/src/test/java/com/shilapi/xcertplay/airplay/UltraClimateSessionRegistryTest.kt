// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class UltraClimateSessionRegistryTest {
    private class Backend : UltraClimateBackend, Closeable {
        var closed = 0
        var valid = true
        override fun capabilities() = if (closed > 0) emptyList() else listOf(
            UltraClimateBackend.Capability(UltraClimateBackend.Zone.DRIVER, 16.0, 32.0, 0.5,
                UltraClimateBackend.Seat.FRONT_RIGHT))
        override fun snapshot() = if (closed > 0 || !valid) null else
            UltraClimateBackend.Snapshot(mapOf(UltraClimateBackend.Zone.DRIVER to 22.0))
        override fun requestTemperature(zone: UltraClimateBackend.Zone, celsius: Double, complete: (Boolean) -> Unit): Closeable =
            Closeable {}
        override fun close() { closed++ }
        fun owned() = UltraClimateSessionRegistry.OwnedBackend(this, this)
    }

    @Test fun defaultRegistryCannotAcquireAnyVehicleAccess() {
        UltraClimateSessionRegistry().use { assertNull(it.acquire()) }
    }

    @Test fun eachSessionOwnsItsBackendAndReleaseIsIdempotent() {
        val registry = UltraClimateSessionRegistry()
        val backends = mutableListOf<Backend>()
        registry.register { Backend().also(backends::add).owned() }.use {
            val a = registry.acquire()!!
            val b = registry.acquire()!!
            assertNotSame(a.backend, b.backend)
            a.close(); a.close()
            assertEquals(1, backends[0].closed)
            assertEquals(0, backends[1].closed)
            b.close()
            assertEquals(1, backends[1].closed)
        }
        registry.close()
    }

    @Test fun revokedBrokerClosesLiveSessionsAndAllowsExplicitReplacement() {
        val registry = UltraClimateSessionRegistry()
        val old = Backend()
        val registration = registry.register { old.owned() }
        registry.acquire()!!
        registration.close(); registration.close()
        assertEquals(1, old.closed)
        assertNull(registry.acquire())
        val next = Backend()
        registry.register { next.owned() }
        assertNotNull(registry.acquire())
        registry.close()
        assertEquals(1, next.closed)
    }

    @Test fun unavailableFactoryOutputIsClosedWithoutAcquiringLease() {
        val registry = UltraClimateSessionRegistry()
        val backend = Backend().apply { valid = false }
        registry.register { backend.owned() }
        assertNull(registry.acquire())
        assertEquals(1, backend.closed)
        registry.close()
    }

    @Test fun revocationDuringOpeningCannotPublishBackendAfterPermissionWasWithdrawn() {
        val registry = UltraClimateSessionRegistry()
        val opened = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val backend = Backend()
        val registration = registry.register {
            opened.countDown()
            check(proceed.await(4, TimeUnit.SECONDS))
            backend.owned()
        }
        val result = AtomicReference<UltraClimateSessionRegistry.Lease?>()
        val thread = Thread { result.set(registry.acquire()) }.apply { start() }
        assertTrue(opened.await(4, TimeUnit.SECONDS))
        registration.close()
        proceed.countDown()
        thread.join(4000)
        assertFalse(thread.isAlive)
        assertNull(result.get())
        assertEquals(1, backend.closed)
        registry.close()
    }

    @Test fun revokedLeaseGatesInertOwnerAndDiscardsLateSuccessFromLiveRawBackend() {
        val registry = UltraClimateSessionRegistry()
        val rawCalls = AtomicInteger()
        var completion: ((Boolean) -> Unit)? = null
        var cancellations = 0
        val raw = object : UltraClimateBackend {
            override fun capabilities(): List<UltraClimateBackend.Capability> {
                rawCalls.incrementAndGet()
                return listOf(UltraClimateBackend.Capability(UltraClimateBackend.Zone.DRIVER,
                    16.0, 32.0, 0.5, UltraClimateBackend.Seat.FRONT_LEFT))
            }
            override fun snapshot(): UltraClimateBackend.Snapshot {
                rawCalls.incrementAndGet()
                return UltraClimateBackend.Snapshot(mapOf(UltraClimateBackend.Zone.DRIVER to 22.0))
            }
            override fun requestTemperature(zone: UltraClimateBackend.Zone, celsius: Double,
                complete: (Boolean) -> Unit): Closeable {
                rawCalls.incrementAndGet()
                completion = complete
                return Closeable { cancellations++ }
            }
        }
        val ownerCloses = AtomicInteger()
        val registration = registry.register {
            UltraClimateSessionRegistry.OwnedBackend(raw, Closeable { ownerCloses.incrementAndGet() })
        }
        val lease = registry.acquire()!!
        val replies = mutableListOf<Boolean>()
        lease.backend.requestTemperature(UltraClimateBackend.Zone.DRIVER, 23.0, replies::add)
        registration.close()
        assertEquals(listOf(false), replies)
        assertEquals(1, cancellations)
        completion!!(true)
        assertEquals(listOf(false), replies)
        val before = rawCalls.get()
        assertTrue(lease.backend.capabilities().isEmpty())
        assertNull(lease.backend.snapshot())
        lease.backend.requestTemperature(UltraClimateBackend.Zone.DRIVER, 24.0, replies::add)
        assertEquals(listOf(false, false), replies)
        assertEquals(before, rawCalls.get())
        assertEquals(1, ownerCloses.get())
        // The raw backend remains readable: the lease itself enforces revocation.
        assertNotNull(raw.snapshot())
        lease.close()
        assertEquals(1, ownerCloses.get())
        registry.close()
    }

    @Test fun synchronousCompletionIsDeliveredOutsideDispatchAndCannotRepeat() {
        val registry = UltraClimateSessionRegistry()
        val raw = Backend()
        val adapter = object : UltraClimateBackend by raw {
            override fun requestTemperature(zone: UltraClimateBackend.Zone, celsius: Double,
                complete: (Boolean) -> Unit): Closeable {
                complete(true)
                complete(false)
                return Closeable {}
            }
        }
        registry.register { UltraClimateSessionRegistry.OwnedBackend(adapter, Closeable {}) }
        val lease = registry.acquire()!!
        val replies = mutableListOf<Boolean>()
        val cancellation = lease.backend.requestTemperature(UltraClimateBackend.Zone.DRIVER, 23.0) {
            replies += it
            lease.close()
        }
        cancellation.close()
        assertEquals(listOf(true), replies)
        assertTrue(lease.backend.capabilities().isEmpty())
        registry.close()
    }

    @Test fun acquisitionCountIsBoundedAndReleasedSlotsCanBeReused() {
        val registry = UltraClimateSessionRegistry()
        val opens = AtomicInteger()
        registry.register { opens.incrementAndGet(); Backend().owned() }
        val leases = List(4) { registry.acquire()!! }
        assertNull(registry.acquire())
        assertEquals(4, opens.get())
        leases[0].close()
        assertNotNull(registry.acquire())
        assertEquals(5, opens.get())
        registry.close()
    }
}
