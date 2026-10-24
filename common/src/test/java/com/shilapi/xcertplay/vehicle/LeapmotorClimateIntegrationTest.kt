package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.shilapi.xcertplay.airplay.UltraClimateBackend.Zone
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorClimateSide
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorClimateVerification
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LeapmotorClimateIntegrationTest {
    private val app: android.app.Application = RuntimeEnvironment.getApplication()
    private class PermissionContext(base: Context, var granted: Boolean) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun checkSelfPermission(permission: String): Int {
            check(permission == "android.car.permission.CONTROL_CAR_CLIMATE")
            return if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
    }
    private fun prepare() {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_META_DATA)
        info.applicationInfo!!.metaData = Bundle().apply {
            putString(VehicleIntegrationPolicy.METADATA_KEY, VehicleIntegrationPolicy.LEAPMOTOR_C11)
        }
        shadowOf(app.packageManager).installPackage(info)
        C11UltraSettings.setEnabled(app, true)
    }
    private fun verification() = LeapmotorClimateVerification(Build.FINGERPRINT,
        mapOf(Zone.DRIVER to LeapmotorClimateSide.RIGHT, Zone.PASSENGER to LeapmotorClimateSide.LEFT),
        LeapmotorClimateSide.entries.associateWith { 0.5 }, "test fixture only")
    private fun acquireOnWorker() {
        val done = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        Thread {
            try { assertNull(LeapmotorClimateIntegration.sessions.acquire()) }
            catch (error: Throwable) { failure.set(error) }
            finally { done.countDown() }
        }.start()
        assertTrue(done.await(3, java.util.concurrent.TimeUnit.SECONDS))
        failure.get()?.let { throw it }
    }

    @Test fun missingClimatePermissionDoesNotOpenManagerEvenWithUltraEnabled() {
        prepare()
        val opens = AtomicInteger()
        LeapmotorClimateIntegration.registerAuthorizedCarPropertyBroker(
            PermissionContext(app, false), verification(), { opens.incrementAndGet(); null }).use {
            acquireOnWorker()
            assertEquals(0, opens.get())
        }
    }

    @Test fun driverOptOutPreventsAuthorizedManagerAcquisition() {
        prepare()
        C11UltraSettings.setEnabled(app, false)
        val opens = AtomicInteger()
        LeapmotorClimateIntegration.registerAuthorizedCarPropertyBroker(
            PermissionContext(app, true), verification(), { opens.incrementAndGet(); null }).use {
            acquireOnWorker()
            assertEquals(0, opens.get())
        }
    }

    @Test fun mainThreadCannotAcquireAndMalformedWorkerManagerIsClosed() {
        prepare()
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        LeapmotorClimateIntegration.registerAuthorizedCarPropertyBroker(
            PermissionContext(app, true), verification(), {
                opens.incrementAndGet()
                LeapmotorClimateIntegration.OwnedCarPropertyManager(Any(), Closeable { closes.incrementAndGet() })
            }).use {
            assertNull(LeapmotorClimateIntegration.sessions.acquire())
            assertEquals(0, opens.get())
            acquireOnWorker()
            assertEquals(1, opens.get())
            assertEquals(1, closes.get())
        }
    }
}
