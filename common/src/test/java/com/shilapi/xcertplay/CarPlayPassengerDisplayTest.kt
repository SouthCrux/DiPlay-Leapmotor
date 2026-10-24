package com.shilapi.xcertplay

import android.app.ActivityOptions
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.Display
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayVideoListener
import com.shilapi.xcertplay.vehicle.VehicleIntegrationPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowDisplayManager
import java.io.File
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayPassengerDisplayTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val routeFile get() = File(app.noBackupFilesDir, CarPlayPassengerDisplay.ROUTE_FILE)
    private val manager get() = app.getSystemService(DisplayManager::class.java)
    private val displays = mutableListOf<Int>()
    private val originalFingerprint = android.os.Build.FINGERPRINT
    private lateinit var context: RecordingContext
    private lateinit var listener: CarPlayVideoListener

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val launches = mutableListOf<Pair<Intent, Bundle?>>()
        var rejectLaunch = false
        override fun getApplicationContext(): Context = this
        override fun startActivity(intent: Intent) { launches += intent to null }
        override fun startActivity(intent: Intent, options: Bundle?) {
            if (rejectLaunch) throw SecurityException("display denied")
            launches += intent to options
        }
    }

    @Before fun setUp() {
        routeFile.delete()
        ShadowBuild.setFingerprint(LeapmotorClusterDisplay.FINGERPRINT)
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 42)
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_META_DATA)
        info.applicationInfo!!.metaData = Bundle().apply {
            putString(VehicleIntegrationPolicy.METADATA_KEY, VehicleIntegrationPolicy.LEAPMOTOR_C11)
        }
        shadowOf(app.packageManager).installPackage(info)
        context = RecordingContext(app)
        listener = attach()
        VideoInCar.allowed = true // Lab gate only; production P/BYD policy is not changed.
    }

    @After fun cleanUp() {
        listener.onVideoSessionEnded()
        shadowOf(Looper.getMainLooper()).idle()
        VideoInCar.allowed = false
        routeFile.delete()
        displays.reversed().forEach(ShadowDisplayManager::removeDisplay)
        ShadowBuild.setFingerprint(originalFingerprint)
    }

    private fun attach(factory: (() -> CarPlayVideoParkedSource?)? = null): CarPlayVideoListener {
        val controller = mock(CarPlayController::class.java)
        CarPlayVideo.attach(context, controller, factory)
        val capture = ArgumentCaptor.forClass(CarPlayVideoListener::class.java)
        verify(controller).videoListener = capture.capture()
        return capture.value
    }

    private fun display(): Display {
        val id = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi")
        displays += id
        return manager.getDisplay(id).also { shadowOf(it).setRotation(0); shadowOf(it).setFlags(0) }
    }

    private fun provision(display: Display, identity: String = "local:130") {
        routeFile.parentFile!!.mkdirs()
        routeFile.writeText(JSONObject().put("schemaVersion", 1).put("bootCount", 42)
            .put("fingerprint", LeapmotorClusterDisplay.FINGERPRINT).put("physicalUniqueId", identity)
            .put("logicalDisplayId", display.displayId).put("modeId", display.mode.modeId)
            .put("width", 1920).put("height", 720).toString())
    }

    private fun play(source: CarPlayVideoListener = listener) {
        source.onVideoMessage(10, mapOf("type" to "insertPlayQueueItem", "item" to
            mapOf("Content-Location" to "https://example.invalid/lab.mp4", "uuid" to "lab")))
        source.onVideoMessage(10, mapOf("type" to "setRate", "rate" to 1))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun sameGeometryAloneDoesNotSelectPassengerOrCreateProof() {
        display(); display()
        assertNull(CarPlayPassengerDisplay.find(context))
        assertFalse(routeFile.exists())
        play()
        assertNull(context.launches.single().second) // Original default launch path.
    }

    @Test fun failedExplicitPassengerSelectionBlocksVideoUntilExplicitRecovery() {
        display()
        assertFalse(CarPlayVideo.selectPassengerDestinationForCurrentSession(context))
        play()
        assertTrue(context.launches.isEmpty())
        assertTrue(CarPlayVideo.selectDefaultDestinationForCurrentSession())
        listener.onVideoUiRequested()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(context.launches.single().second)
    }

    @Test fun provisionedPassengerLaunchCarriesLeaseAndUsesTheMeasuredDisplay() {
        display() // Same-sized cluster/other panel must not win by size or ordering.
        val passenger = display()
        provision(passenger)
        assertTrue(CarPlayVideo.selectPassengerDestinationForCurrentSession(context))
        play()
        val (intent, options) = context.launches.single()
        // fromBundle exists in the Android framework but is hidden from the public SDK.
        val decodedOptions = ActivityOptions::class.java.getDeclaredMethod("fromBundle", Bundle::class.java)
            .invoke(null, options) as ActivityOptions
        assertEquals(passenger.displayId, decodedOptions.launchDisplayId)
        val lease = CarPlayVideo.activityLease(intent, passenger.displayId)!!
        assertEquals(passenger.displayId, lease.passenger!!.logicalDisplayId)
        assertTrue(CarPlayVideo.destinationAvailable(lease, passenger.displayId))
        assertFalse(CarPlayVideo.destinationAvailable(lease, Display.DEFAULT_DISPLAY))
        assertFalse(CarPlayVideo.selectDefaultDestinationForCurrentSession())
    }

    @Test fun staleBootWrongPhysicalIdentityFirmwareGeometryAndModeAreRejected() {
        val passenger = display()
        provision(passenger)
        val valid = routeFile.readText()
        for ((key, value) in listOf("bootCount" to 43, "physicalUniqueId" to "local:131",
            "physicalUniqueId" to "local:4", "fingerprint" to "other", "width" to 2560,
            "height" to 1080, "logicalDisplayId" to 0, "modeId" to passenger.mode.modeId + 1)) {
            routeFile.writeText(JSONObject(valid).put(key, value).toString())
            assertNull("$key=$value", CarPlayPassengerDisplay.find(context))
        }
        routeFile.writeText("{" + " ".repeat(2048))
        assertNull(CarPlayPassengerDisplay.find(context))
        routeFile.writeText(valid)
        shadowOf(passenger).setRotation(1)
        assertNull(CarPlayPassengerDisplay.find(context))
        shadowOf(passenger).setRotation(0)
        shadowOf(passenger).setFlags(Display.FLAG_PRIVATE)
        assertNull(CarPlayPassengerDisplay.find(context))
    }

    @Test fun lossBetweenSelectionAndLaunchNeverFallsBackToDriver() {
        val passenger = display()
        provision(passenger)
        assertTrue(CarPlayVideo.selectPassengerDestinationForCurrentSession(context))
        routeFile.delete()
        play()
        assertTrue(context.launches.isEmpty())
        assertFalse(CarPlayVideo.playing)
    }

    @Test fun androidLaunchDenialDoesNotRetryOnDefaultDisplay() {
        val passenger = display()
        provision(passenger)
        CarPlayVideo.selectPassengerDestinationForCurrentSession(context)
        context.rejectLaunch = true
        play()
        assertTrue(context.launches.isEmpty())
        assertFalse(CarPlayVideo.playing)
    }

    @Test fun reconnectRejectsOldListenerAndLateActivityAndResetsSelection() {
        val passenger = display()
        provision(passenger)
        CarPlayVideo.selectPassengerDestinationForCurrentSession(context)
        play()
        val oldIntent = context.launches.single().first
        val oldListener = listener
        listener = attach()
        oldListener.onVideoSessionEnded()
        play(oldListener)
        assertEquals(1, context.launches.size)
        assertNull(CarPlayVideo.activityLease(oldIntent, passenger.displayId))
        play()
        assertEquals(2, context.launches.size)
        assertNull(context.launches.last().second)
    }

    @Test fun missingParkedAvailabilityCannotBeEnabledBySelectingPassenger() {
        val passenger = display()
        provision(passenger)
        VideoInCar.allowed = false
        assertTrue(CarPlayVideo.selectPassengerDestinationForCurrentSession(context))
        play()
        assertFalse(VideoInCar.allowed)
        assertTrue(context.launches.isEmpty())
    }

    @Test fun wrongActualDisplayActivityExitsBeforeConstructingAPlayerAndDestroysSafely() {
        val passenger = display()
        provision(passenger)
        CarPlayVideo.selectPassengerDestinationForCurrentSession(context)
        play()
        val activity = Robolectric.buildActivity(CarPlayVideoActivity::class.java,
            context.launches.single().first).create()
        assertTrue(activity.get().isFinishing)
        assertNull(CarPlayVideo.activity)
        activity.destroy()
    }

    @Test fun displayRemovalInvalidatesOnlyItsOwnerAndNotifiesExactlyOnce() {
        val passenger = display()
        provision(passenger)
        val route = CarPlayPassengerDisplay.find(context)!!
        var lost = 0
        val monitor = CarPlayVideoDestinationMonitor(context, passenger.displayId,
            available = { CarPlayPassengerDisplay.display(context, route) != null }, onLost = { lost++ })
        monitor.check()
        assertEquals(0, lost)
        ShadowDisplayManager.removeDisplay(passenger.displayId)
        displays.remove(passenger.displayId)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, lost)
        monitor.check()
        monitor.close()
        assertEquals(1, lost)
    }

    @Test fun monitorClosureUnregistersWithoutReportingLossAndProofChangeFailsClosed() {
        val passenger = display()
        provision(passenger)
        val route = CarPlayPassengerDisplay.find(context)!!
        var lost = 0
        val closed = CarPlayVideoDestinationMonitor(context, passenger.displayId,
            available = { false }, onLost = { lost++ })
        closed.close()
        closed.check()
        assertEquals(0, lost)
        val active = CarPlayVideoDestinationMonitor(context, passenger.displayId,
            available = { CarPlayPassengerDisplay.display(context, route) != null }, onLost = { lost++ })
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 43)
        active.check()
        assertEquals(1, lost)
        active.close()
    }

    @Test fun aRemovalEventCannotBeMaskedByAReboundDisplayOrStalePositiveAvailability() {
        val passenger = display()
        var lost = 0
        val monitor = CarPlayVideoDestinationMonitor(context, passenger.displayId,
            available = { true }, onLost = { lost++ })
        ShadowDisplayManager.removeDisplay(passenger.displayId)
        displays.remove(passenger.displayId)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, lost)
        monitor.check()
        monitor.close()
        assertEquals(1, lost)
    }

    @Test fun staleResourceOwnerCannotSendRequestsForAReplacementItemOrSession() {
        play()
        val lease = CarPlayVideo.activityLease(context.launches.single().first, Display.DEFAULT_DISPLAY)!!
        val oldItem = CarPlayVideo.captureResourceOwner(lease)!!
        listener.onVideoMessage(10, mapOf("type" to "insertPlayQueueItem", "item" to
            mapOf("Content-Location" to "https://example.invalid/next.mp4", "uuid" to "next")))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(CarPlayVideo.resolveOnIphone("lab-resource://old-item", oldItem))
        val oldSession = CarPlayVideo.captureResourceOwner(lease)!!
        listener = attach()
        assertNull(CarPlayVideo.resolveOnIphone("lab-resource://old-session", oldSession))
    }

    @Test fun destinationLossRevokesBeforeFinishAndLateRateCannotReactivateThePlayer() {
        val passenger = display()
        provision(passenger)
        assertTrue(CarPlayVideo.selectPassengerDestinationForCurrentSession(context))
        play()
        val lease = CarPlayVideo.activityLease(context.launches.single().first, passenger.displayId)!!
        val oldPlayer = mock(CarPlayVideoActivity::class.java)
        `when`(oldPlayer.videoDisplayId()).thenReturn(passenger.displayId)
        assertTrue(CarPlayVideo.playerReady(oldPlayer, lease))
        CarPlayVideo.destinationLost(oldPlayer, lease)
        assertFalse(CarPlayVideo.isCurrentPlayer(oldPlayer, lease))
        assertNull(CarPlayVideo.activity)
        assertFalse(CarPlayVideo.playing)
        listener.onVideoMessage(10, mapOf("type" to "setRate", "rate" to 1))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, context.launches.size)
        verify(oldPlayer, never()).applyRate()
        assertTrue(CarPlayVideo.selectPassengerDestinationForCurrentSession(context))
        listener.onVideoUiRequested()
        shadowOf(Looper.getMainLooper()).idle()
        val current = context.launches.last().first
        assertEquals(2, context.launches.size)
        CarPlayVideo.playerDestroyed(oldPlayer, lease, 100)
        assertNotNull(CarPlayVideo.activityLease(current, passenger.displayId))
    }

    @Test fun ordinaryVideoReconnectsOnTheSameControllerAndRejectsItsOldActivity() {
        listener.onVideoSessionStarted()
        shadowOf(Looper.getMainLooper()).idle()
        play()
        val old = context.launches.single().first
        listener.onVideoSessionEnded()
        listener.onVideoSessionStarted()
        play()
        assertEquals(2, context.launches.size)
        assertNull(context.launches.last().second)
        assertNull(CarPlayVideo.activityLease(old, Display.DEFAULT_DISPLAY))
        assertNotNull(CarPlayVideo.activityLease(context.launches.last().first, Display.DEFAULT_DISPLAY))
    }

    @Test fun eachRealSessionOwnsItsExplicitParkedSourceAndResetsPassengerOptIn() {
        val sources = mutableListOf<CarPlayVideoParkedSource>()
        var closed = 0
        listener = attach {
            object : CarPlayVideoParkedSource {
                override fun readParked(): Boolean? = true
                override fun close() { closed++ }
            }.also(sources::add)
        }
        assertNull(listener.readParked())
        assertTrue(sources.isEmpty())
        listener.onVideoSessionStarted()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true, listener.readParked())
        val passenger = display()
        provision(passenger)
        assertTrue(CarPlayVideo.selectPassengerDestinationForCurrentSession(context))
        play()
        assertNotNull(context.launches.single().second)
        listener.onVideoSessionEnded()
        listener.onVideoSessionStarted()
        assertEquals(1, closed)
        assertEquals(true, listener.readParked())
        play()
        assertEquals(2, sources.size)
        assertNull(context.launches.last().second)
        (listener as Closeable).close()
        (listener as Closeable).close()
        assertEquals(2, closed)
        assertNull(listener.readParked())
    }

    @Test fun startedAndEndedFenceOldStreamBeforeTheMainThreadFinishesThePreviousPlayer() {
        val controller = mock(CarPlayController::class.java)
        CarPlayVideo.attach(context, controller)
        val capture = ArgumentCaptor.forClass(CarPlayVideoListener::class.java)
        verify(controller).videoListener = capture.capture()
        listener = capture.value
        listener.onVideoSessionStarted()
        play()
        assertNotNull(CarPlayVideo.captureResourceOwner())
        // Deliberately do not drain the posted stop: metadata must already belong to no item.
        listener.onVideoSessionStarted()
        assertNull(CarPlayVideo.captureResourceOwner())
        CarPlayVideo.setPlaying(false)
        play()
        assertNotNull(CarPlayVideo.captureResourceOwner())
        listener.onVideoSessionEnded()
        assertNull(CarPlayVideo.captureResourceOwner())
        CarPlayVideo.setPlaying(false)
        val sender = CarPlayVideo.javaClass.getDeclaredField("sender").apply { isAccessible = true }
            .get(CarPlayVideo) as ExecutorService
        sender.submit {}.get(3, TimeUnit.SECONDS)
        verify(controller, never()).sendVideoMessage(anyLong(), anyMap())
    }
}
