package com.shilapi.xcertplay.vehicle

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import android.content.Context
import android.content.ServiceConnection
import android.content.ComponentName
import android.os.Handler
import android.os.Binder
import android.os.SystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LeapmotorParkedIntegrationTest {
    @Test fun disallowedLeaseStaysUnknownAndCanBeClosedRepeatedly() {
        val lease = LeapmotorParkedIntegration.Lease(RuntimeEnvironment.getApplication(), false)
        assertNull(lease.readParked())
        lease.close(); lease.close()
        assertNull(lease.readParked())
    }
    @Test fun unsupportedProfileAcquisitionDoesNotInferParked() {
        LeapmotorParkedIntegration.acquire(RuntimeEnvironment.getApplication()).use {
            assertNull(it.readParked())
        }
    }
    class FakeCar(private val connection: ServiceConnection) {
        fun connect() { connection.onServiceConnected(ComponentName("test", "Car"), Binder()) }
        fun getCarManager(service: String): Any {
            check(service == "property")
            if (failManager) error("simulated manager unavailable")
            return Manager()
        }
        fun disconnect() { disconnected.countDown() }
        companion object {
            @Volatile var failManager = false
            @Volatile var disconnected = CountDownLatch(1)
            @Volatile var readBlock: CountDownLatch? = null
            @Volatile var readEntered: CountDownLatch? = null
            @Volatile var readFinished: CountDownLatch? = null
            @JvmStatic fun createCar(context: Context, connection: ServiceConnection, handler: Handler): FakeCar {
                check(context.applicationContext != null)
                check(handler.looper != null)
                return FakeCar(connection)
            }
        }
    }
    class Manager {
        fun getPropertyList() = listOf(Configuration())
        fun getProperty(id: Int, area: Int): Reading {
            check(id == 289408000 && area == 0)
            FakeCar.readEntered?.countDown()
            try {
                FakeCar.readBlock?.let { check(it.await(4, TimeUnit.SECONDS)) }
                return Reading(SystemClock.elapsedRealtimeNanos())
            } finally { FakeCar.readFinished?.countDown() }
        }
    }
    class Configuration {
        fun getPropertyId() = 289408000
        fun getAccess() = 1
        fun isGlobalProperty() = true
        fun getPropertyType(): Class<*> = java.lang.Integer::class.java
        fun getChangeMode() = 1
    }
    class Reading(private val timestamp: Long) {
        fun getPropertyId() = 289408000
        fun getAreaId() = 0
        fun getStatus() = 0
        fun getTimestamp() = timestamp
        fun getValue() = 4
    }
    @Test fun enabledPlatformConnectorReadsFreshParkAndCloseDisconnectsAndRevokesCache() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        FakeCar.failManager = false
        FakeCar.disconnected = CountDownLatch(1)
        val lease = LeapmotorParkedIntegration.Lease(RuntimeEnvironment.getApplication(), true) { FakeCar::class.java }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (lease.readParked() == null && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(true, lease.readParked())
        } finally { lease.close() }
        assertNull(lease.readParked())
        assertTrue(FakeCar.disconnected.await(4, TimeUnit.SECONDS))
        lease.close()
        assertNull(lease.readParked())
    }
    @Test fun enabledConnectorManagerFailureDisconnectsAndNeverInfersParked() {
        FakeCar.failManager = true
        FakeCar.disconnected = CountDownLatch(1)
        val lease = LeapmotorParkedIntegration.Lease(RuntimeEnvironment.getApplication(), true) { FakeCar::class.java }
        try {
            assertTrue(FakeCar.disconnected.await(4, TimeUnit.SECONDS))
            assertNull(lease.readParked())
        } finally { lease.close(); FakeCar.failManager = false }
    }
    @Test fun missingOptionalPlatformClassFailsClosed() {
        val lease = LeapmotorParkedIntegration.Lease(RuntimeEnvironment.getApplication(), true) {
            throw ClassNotFoundException("simulated missing android.car")
        }
        lease.close()
        assertNull(lease.readParked())
    }
    @Test fun blockedReadsRetainOwnershipCapEvenAfterDisconnectCompletes() {
        FakeCar.failManager = false
        FakeCar.disconnected = CountDownLatch(4)
        val unblock = CountDownLatch(1)
        val entered = CountDownLatch(4)
        val finished = CountDownLatch(4)
        FakeCar.readBlock = unblock
        FakeCar.readEntered = entered
        FakeCar.readFinished = finished
        val leases = mutableListOf<LeapmotorParkedIntegration.Lease>()
        try {
            repeat(4) {
                leases += LeapmotorParkedIntegration.Lease(RuntimeEnvironment.getApplication(), true) { FakeCar::class.java }
            }
            assertTrue(entered.await(4, TimeUnit.SECONDS))
            leases.forEach { it.close(); assertNull(it.readParked()) }
            assertTrue(FakeCar.disconnected.await(4, TimeUnit.SECONDS))
            val called = CountDownLatch(1)
            LeapmotorParkedIntegration.Lease(RuntimeEnvironment.getApplication(), true) {
                called.countDown()
                FakeCar::class.java
            }.use { rejected ->
                assertNull(rejected.readParked())
                assertFalse("A blocked retired reader must still retain its ownership slot",
                    called.await(150, TimeUnit.MILLISECONDS))
            }
        } finally {
            unblock.countDown()
            leases.forEach { it.close() }
            if (leases.size == 4) assertTrue(finished.await(4, TimeUnit.SECONDS))
            FakeCar.readBlock = null
            FakeCar.readEntered = null
            FakeCar.readFinished = null
        }
    }
    @Test fun blockedPollExpiresHalDeliveryAndDiagnosticsKeepBothClocksDistinct() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        FakeCar.failManager = false
        FakeCar.disconnected = CountDownLatch(1)
        val lease = LeapmotorParkedIntegration.Lease(RuntimeEnvironment.getApplication(), true) { FakeCar::class.java }
        val unblock = CountDownLatch(1)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (lease.readParked() == null && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(true, lease.readParked())
            val before = lease.diagnosticSnapshot()
            assertEquals("CURRENT_HAL_SNAPSHOT", before.freshnessPolicy)
            assertNotNull(before.oemEventAgeMillis)
            assertNotNull(before.halDeliveryAgeMillis)
            val entered = CountDownLatch(1)
            FakeCar.readEntered = entered
            FakeCar.readBlock = unblock
            assertTrue(entered.await(4, TimeUnit.SECONDS))
            ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
            assertNull(lease.readParked())
            assertTrue(lease.diagnosticSnapshot().halDeliveryAgeMillis!! >= 6000)
        } finally {
            lease.close()
            unblock.countDown()
            FakeCar.readEntered = null
            FakeCar.readBlock = null
        }
        assertNull(lease.readParked())
        assertEquals("CLOSED_OR_DISABLED", lease.diagnosticSnapshot().sourceStatus)
    }
}
