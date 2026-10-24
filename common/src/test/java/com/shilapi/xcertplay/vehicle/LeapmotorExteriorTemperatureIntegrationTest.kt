package com.shilapi.xcertplay.vehicle

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LeapmotorExteriorTemperatureIntegrationTest {
    class AllowedContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        @Volatile var permitted = true
        override fun checkSelfPermission(permission: String) =
            if (permitted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }
    class FakeCar(private val connection: ServiceConnection) {
        fun connect() {
            connections.add(connection)
            connection.onServiceConnected(ComponentName("test", "Car"), Binder())
        }
        fun getCarManager(service: String): Any { check(service == "property"); return Manager() }
        fun disconnect() { disconnected.countDown() }
        companion object {
            val connections = CopyOnWriteArrayList<ServiceConnection>()
            val created = AtomicInteger()
            val registrations = AtomicInteger()
            val removals = AtomicInteger()
            @Volatile var callback: CarPropertyEventCallback? = null
            @Volatile var removed: CarPropertyEventCallback? = null
            @Volatile var acceptedRegistration = true
            @Volatile var registerEntered: CountDownLatch? = null
            @Volatile var registerBlock: CountDownLatch? = null
            @Volatile var disconnected = CountDownLatch(1)
            @Volatile var readBlock: CountDownLatch? = null
            @Volatile var readEntered: CountDownLatch? = null
            @Volatile var readFinished: CountDownLatch? = null
            @JvmStatic fun createCar(context: Context, connection: ServiceConnection, handler: Handler): FakeCar {
                check(context.applicationContext != null && handler.looper != null)
                created.incrementAndGet()
                return FakeCar(connection)
            }
        }
    }
    interface CarPropertyEventCallback {
        fun onChangeEvent(value: Any?)
        fun onErrorEvent(property: Int, area: Int)
    }
    class Manager {
        fun registerCallback(callback: CarPropertyEventCallback, property: Int, rate: Float): Boolean {
            check(property == 291505923 && rate == 1f)
            check(Looper.myLooper() != Looper.getMainLooper())
            FakeCar.callback = callback; FakeCar.registrations.incrementAndGet()
            FakeCar.registerEntered?.countDown()
            FakeCar.registerBlock?.let { check(it.await(4, TimeUnit.SECONDS)) }
            return FakeCar.acceptedRegistration
        }
        fun unregisterCallback(callback: CarPropertyEventCallback, property: Int) {
            check(property == 291505923)
            check(Looper.myLooper() != Looper.getMainLooper())
            FakeCar.removed = callback; FakeCar.removals.incrementAndGet()
        }
        fun getPropertyList() = listOf(Configuration())
        fun getProperty(id: Int, area: Int): Reading {
            check(id == 291505923 && area == 0)
            val timestamp = SystemClock.elapsedRealtimeNanos()
            FakeCar.readEntered?.countDown()
            try {
                FakeCar.readBlock?.let { check(it.await(4, TimeUnit.SECONDS)) }
                return Reading(timestamp)
            } finally { FakeCar.readFinished?.countDown() }
        }
    }
    class Configuration {
        fun getPropertyId() = 291505923
        fun getAccess() = 1
        fun isGlobalProperty() = true
        fun getPropertyType(): Class<*> = java.lang.Float::class.java
        fun getChangeMode() = 2
    }
    class Reading(private val timestamp: Long) {
        fun getPropertyId() = 291505923
        fun getAreaId() = 0
        fun getStatus() = 0
        fun getTimestamp() = timestamp
        fun getValue() = -3.25f
    }
    private fun awaitCondition(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!condition() && System.nanoTime() < end) Thread.sleep(10)
        assertTrue(condition())
    }
    private fun reset() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        FakeCar.created.set(0); FakeCar.connections.clear()
        FakeCar.registrations.set(0); FakeCar.removals.set(0)
        FakeCar.callback = null; FakeCar.removed = null
        FakeCar.acceptedRegistration = true
        FakeCar.registerEntered = null; FakeCar.registerBlock = null
        FakeCar.disconnected = CountDownLatch(1)
        FakeCar.readBlock = null; FakeCar.readEntered = null; FakeCar.readFinished = null
    }
    @Test fun disabledAndMissingPlatformStayUnknownAndCloseIsIdempotent() {
        val context = RuntimeEnvironment.getApplication()
        LeapmotorExteriorTemperatureIntegration.Lease(context, false).use {
            assertNull(it.readCelsius())
            assertTrue(it.diagnosticReport().contains("celsius=UNKNOWN"))
            it.close()
        }
        LeapmotorExteriorTemperatureIntegration.Lease(context, true) {
            throw ClassNotFoundException("simulated optional platform absence")
        }.use { assertNull(it.readCelsius()) }
    }
    @Test fun workerConnectsAndFreshCacheExpiresWhileBlockedWithoutUiBinderReads() {
        reset()
        val context = AllowedContext(RuntimeEnvironment.getApplication())
        val lease = LeapmotorExteriorTemperatureIntegration.Lease(context, true) { FakeCar::class.java }
        val unblock = CountDownLatch(1)
        val finished = CountDownLatch(1)
        try {
            awaitCondition { lease.readCelsius() != null }
            assertEquals(-3.25f, lease.readCelsius()!!, 0f)
            assertTrue(lease.diagnosticReport().contains("oemEventAgeMs="))
            assertTrue(lease.diagnosticReport().contains("halDeliveryAgeMs="))
            val entered = CountDownLatch(1)
            FakeCar.readBlock = unblock; FakeCar.readEntered = entered; FakeCar.readFinished = finished
            assertTrue(entered.await(4, TimeUnit.SECONDS))
            ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
            assertNull(lease.readCelsius())
            assertTrue(lease.diagnosticReport().contains("status=STALE celsius=UNKNOWN"))
            unblock.countDown()
            assertTrue(finished.await(4, TimeUnit.SECONDS))
            awaitCondition { lease.diagnosticReport().contains("status=STALE") }
            assertNull(lease.readCelsius())
        } finally {
            lease.close(); unblock.countDown()
            FakeCar.readBlock = null; FakeCar.readEntered = null; FakeCar.readFinished = null
        }
        assertNull(lease.readCelsius())
        assertTrue(FakeCar.disconnected.await(4, TimeUnit.SECONDS))
        assertEquals(1, FakeCar.registrations.get())
        assertEquals(1, FakeCar.removals.get())
        assertSame(FakeCar.callback, FakeCar.removed)
    }
    @Test fun rejectedRegistrationAndCloseDuringRegistrationCleanUpBeforeDisconnect() {
        reset()
        val context = AllowedContext(RuntimeEnvironment.getApplication())
        FakeCar.acceptedRegistration = false
        val failed = LeapmotorExteriorTemperatureIntegration.Lease(context, true) { FakeCar::class.java }
        try {
            assertTrue(FakeCar.disconnected.await(4, TimeUnit.SECONDS))
            assertNull(failed.readCelsius())
            assertEquals(1, FakeCar.removals.get())
            assertSame(FakeCar.callback, FakeCar.removed)
        } finally { failed.close() }
        reset()
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
        FakeCar.registerEntered = entered; FakeCar.registerBlock = unblock
        val retired = LeapmotorExteriorTemperatureIntegration.Lease(context, true) { FakeCar::class.java }
        try {
            assertTrue(entered.await(4, TimeUnit.SECONDS))
            retired.close()
            assertNull(retired.readCelsius())
            unblock.countDown()
            assertTrue(FakeCar.disconnected.await(4, TimeUnit.SECONDS))
            assertEquals(1, FakeCar.removals.get())
            assertSame(FakeCar.callback, FakeCar.removed)
            FakeCar.callback!!.onChangeEvent(Reading(SystemClock.elapsedRealtimeNanos()))
            assertNull(retired.readCelsius())
        } finally {
            retired.close(); unblock.countDown()
            FakeCar.registerEntered = null; FakeCar.registerBlock = null
        }
    }
    @Test fun matchingCallbackErrorClosesSourceAndRemovesSubscription() {
        reset()
        val context = AllowedContext(RuntimeEnvironment.getApplication())
        val lease = LeapmotorExteriorTemperatureIntegration.Lease(context, true) { FakeCar::class.java }
        try {
            awaitCondition { lease.readCelsius() != null }
            FakeCar.callback!!.onErrorEvent(291505923, 0)
            assertNull(lease.readCelsius())
            assertTrue(FakeCar.disconnected.await(4, TimeUnit.SECONDS))
            assertSame(FakeCar.callback, FakeCar.removed)
            assertEquals(1, FakeCar.removals.get())
        } finally { lease.close() }
    }
    @Test fun permissionRevokedDuringGetWithdrawsAndUnregistersRatherThanPublishLateValue() {
        reset()
        val context = AllowedContext(RuntimeEnvironment.getApplication())
        val lease = LeapmotorExteriorTemperatureIntegration.Lease(context, true) { FakeCar::class.java }
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
        try {
            awaitCondition { lease.readCelsius() != null }
            FakeCar.readEntered = entered; FakeCar.readBlock = unblock
            assertTrue(entered.await(4, TimeUnit.SECONDS))
            context.permitted = false
            unblock.countDown()
            assertTrue(FakeCar.disconnected.await(4, TimeUnit.SECONDS))
            assertNull(lease.readCelsius())
            assertEquals(1, FakeCar.removals.get())
            assertSame(FakeCar.callback, FakeCar.removed)
        } finally {
            lease.close(); unblock.countDown()
            FakeCar.readEntered = null; FakeCar.readBlock = null
        }
    }
    @Test fun firstAndLastOwnersShareConnectionAndRetiredCallbacksCannotRestoreIt() {
        reset()
        val application = RuntimeEnvironment.getApplication()
        val info = application.packageManager.getPackageInfo(application.packageName, PackageManager.GET_META_DATA)
        info.applicationInfo!!.metaData = Bundle().apply {
            putString(VehicleIntegrationPolicy.METADATA_KEY, VehicleIntegrationPolicy.LEAPMOTOR_C11)
        }
        shadowOf(application.packageManager).installPackage(info)
        val context = AllowedContext(application)
        val activity = Any(); val controller = Any()
        val integration = LeapmotorExteriorTemperatureIntegration
        integration.acquire(context, activity) { FakeCar::class.java }
        integration.acquire(context, controller) { FakeCar::class.java }
        try {
            awaitCondition { integration.diagnosticReport(context).contains("celsius=-3.25") }
            assertEquals(1, FakeCar.created.get())
            integration.release(activity)
            assertTrue(integration.diagnosticReport(context).contains("celsius=-3.25"))
            assertEquals(1L, FakeCar.disconnected.count)
            val retired = FakeCar.connections.single()
            integration.release(controller)
            assertTrue(FakeCar.disconnected.await(4, TimeUnit.SECONDS))
            retired.onServiceConnected(ComponentName("test", "Car"), Binder())
            assertTrue(integration.diagnosticReport(context).contains("source=STOPPED"))
            assertEquals(1, FakeCar.created.get())
        } finally { integration.release(activity); integration.release(controller) }
    }
}
