package com.shilapi.xcertplay

import android.content.pm.PackageManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.vehicle.VehicleIntegrationPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowSettings
import org.json.JSONObject
import java.io.File
import org.mockito.Mockito.*
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class LeapmotorClusterDisplayTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(DisplayManager::class.java)
    private val routeFile get() = File(context.noBackupFilesDir, LeapmotorClusterDisplay.ROUTE_FILE)
    private val originalFingerprint = android.os.Build.FINGERPRINT
    private val displays = mutableListOf<Int>()

    @Before fun profile() {
        routeFile.delete()
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 42)
        ShadowSettings.setCanDrawOverlays(true)
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_META_DATA)
        info.applicationInfo!!.metaData = Bundle().apply {
            putString(VehicleIntegrationPolicy.METADATA_KEY, VehicleIntegrationPolicy.LEAPMOTOR_C11)
        }
        shadowOf(context.packageManager).installPackage(info)
        ShadowBuild.setFingerprint(LeapmotorClusterDisplay.FINGERPRINT)
    }

    @After fun cleanup() {
        routeFile.delete()
        displays.reversed().forEach(ShadowDisplayManager::removeDisplay)
        ShadowBuild.setFingerprint(originalFingerprint)
    }

    private fun display(uniqueId: String = LeapmotorClusterDisplay.UNIQUE_ID): Display {
        val id = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi")
        displays += id
        val real = manager.getDisplay(id)
        shadowOf(real).setRotation(0)
        shadowOf(real).setFlags(0)
        if (uniqueId == LeapmotorClusterDisplay.UNIQUE_ID) writeRoute(real)
        return real
    }

    private fun writeRoute(display: Display) {
        routeFile.parentFile!!.mkdirs()
        routeFile.writeText(JSONObject().put("schemaVersion", 1).put("bootCount", 42)
            .put("fingerprint", LeapmotorClusterDisplay.FINGERPRINT)
            .put("physicalUniqueId", LeapmotorClusterDisplay.UNIQUE_ID)
            .put("logicalDisplayId", display.displayId).put("modeId", display.mode.modeId)
            .put("width", 1920).put("height", 720).toString())
    }

    @Test fun selectsProvisionedIdentityWithoutDependingOnFixedLogicalIdOrPresentationFlag() {
        val passenger = display("local:130")
        display("local:4")
        val cluster = display()
        assertEquals(0, cluster.flags and Display.FLAG_PRESENTATION)
        assertNotEquals(passenger.displayId, cluster.displayId)
        assertNotEquals(2, cluster.displayId)
        assertTrue("${android.os.Build.FINGERPRINT}; $cluster; ${ClusterMapPresentation.sizeOf(cluster)}",
            LeapmotorClusterDisplay.accepts(context, cluster))
        assertEquals(cluster.displayId, ClusterMapPresentation.findDisplay(context)?.displayId)
    }

    @Test fun neverFallsBackToPassengerHdmiOrBydWithoutAValidatedRoute() {
        display("local:130")
        val byd = display("virtual:byd")
        shadowOf(byd).setName(DiLink4ClusterDisplay.NAME)
        shadowOf(byd).setFlags(Display.FLAG_PRESENTATION)
        display("local:4")
        assertNull(ClusterMapPresentation.findDisplay(context))
    }

    @Test fun changedFirmwareGeometryRotationPrivacyOrDefaultIdentityAreRejected() {
        fun match(firmware: String = LeapmotorClusterDisplay.FINGERPRINT, id: String = "local:131",
                  width: Int = 1920, height: Int = 720, rotation: Int = 0,
                  valid: Boolean = true, privateDisplay: Boolean = false, default: Boolean = false) =
            LeapmotorClusterDisplay.matches(firmware, id, width, height, rotation, valid, privateDisplay, default)
        assertTrue(match())
        assertFalse(match(firmware = "other")); assertFalse(match(id = "local:130"))
        assertFalse(match(width = 2560)); assertFalse(match(height = 1080))
        assertFalse(match(rotation = 1)); assertFalse(match(valid = false))
        assertFalse(match(privateDisplay = true)); assertFalse(match(default = true))
        display()
        ShadowBuild.setFingerprint("other")
        assertNull(ClusterMapPresentation.findDisplay(context))
    }

    @Test fun staleBootChangedModeMalformedOrOversizedRoutesAreRejected() {
        val display = display()
        assertNotNull(ClusterMapPresentation.findDisplay(context))
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 43)
        assertNull(ClusterMapPresentation.findDisplay(context))
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 42)
        routeFile.writeText(JSONObject(routeFile.readText()).put("modeId", display.mode.modeId + 1).toString())
        assertNull(ClusterMapPresentation.findDisplay(context))
        routeFile.writeText("{broken")
        assertNull(ClusterMapPresentation.findDisplay(context))
        routeFile.writeText(" ".repeat(2049))
        assertNull(ClusterMapPresentation.findDisplay(context))
    }

    private fun measurement(display: Display, identity: String = "local:131") =
        "mBaseDisplayInfo=DisplayInfo{\"Pantalla integrada\", displayId ${display.displayId}\", " +
            "real 1920 x 720, mode ${display.mode.modeId}, rotation 0, uniqueId \"$identity\", app 1920 x 720}"

    @Test fun rebootRenewsRouteOnlyAfterFreshPhysicalMeasurementAndTracksChangedLogicalId() {
        val old = display()
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 43)
        val current = display("local:131-new-logical-id")
        assertNull(LeapmotorClusterDisplay.findDisplay(context))
        assertNotEquals(old.displayId, current.displayId)
        val passenger = display("local:130")
        var calls = 0
        assertTrue(LeapmotorClusterDisplay.refreshRoute(context) { command ->
            calls++
            assertEquals("dumpsys display", command)
            measurement(passenger, "local:130") + "\n" + measurement(current)
        })
        assertEquals(current.displayId, LeapmotorClusterDisplay.findDisplay(context)?.displayId)
        assertEquals(43, JSONObject(routeFile.readText()).getInt("bootCount"))
        assertFalse(LeapmotorClusterDisplay.refreshRoute(context) { error("Current route must not query ADB") })
        assertEquals(1, calls)
    }

    @Test fun renewalRejectsAmbiguousPassengerGeometryAndUnavailableMeasurements() {
        val display = display()
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 43)
        val original = routeFile.readText()
        for (text in listOf(null, measurement(display, "local:130"),
                measurement(display) + "\n" + measurement(display),
                measurement(display).replace("real 1920 x 720,", "real 1920 x 1080,"),
                measurement(display).replace("rotation 0,", "rotation 1,"),
                measurement(display).replace("real ", "FLAG_PRIVATE, real "),
                measurement(display).replace("mBaseDisplayInfo", "mOverrideDisplayInfo"))) {
            assertFalse(LeapmotorClusterDisplay.refreshRoute(context) { text })
            assertEquals(original, routeFile.readText())
            assertNull(LeapmotorClusterDisplay.findDisplay(context))
        }
        assertFalse(LeapmotorClusterDisplay.refreshRoute(context) {
            Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 44)
            measurement(display)
        })
        assertEquals(original, routeFile.readText())
    }

    @Test fun presentationOnlyOccupiesTheNavigationTileAndDisappearsWithoutAStream() {
        val presentation = LeapmotorClusterMapOverlay(context, display()) {}
        try {
            presentation.show()
            val attributes = presentation.layoutParams
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, attributes.type)
            assertEquals(1200, attributes.x); assertEquals(0, attributes.y)
            assertEquals(720, attributes.width); assertEquals(720, attributes.height)
            assertEquals(1920, attributes.x + attributes.width)
            assertEquals(720, attributes.y + attributes.height)
            assertTrue(attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertEquals(0, attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            assertEquals(0f, attributes.alpha, 0f)
            presentation.setStreamActive(true)
            assertEquals(1f, presentation.layoutParams.alpha, 0f)
            presentation.setMapVisible(false)
            assertEquals(0f, presentation.layoutParams.alpha, 0f)
            presentation.setStreamActive(false)
            presentation.setMapVisible(true)
            assertEquals(0f, presentation.layoutParams.alpha, 0f)
        } finally { presentation.dismiss() }
    }

    @Test fun semanticGuidanceRemainsVisibleWithoutAnAlternateMapStreamAndClearsWithTheRoute() {
        val presentation = LeapmotorClusterMapOverlay(context, display()) {}
        try {
            presentation.show()
            presentation.setTurnCardGuidance(com.shilapi.xcertplay.hud.ClusterTurnGuidance(3, 0, 250, "Avenida"))
            assertEquals(1f, presentation.layoutParams.alpha, 0f)
            presentation.setStreamActive(true)
            presentation.setStreamActive(false)
            assertEquals("A map interruption must not hide fresh semantic guidance", 1f, presentation.layoutParams.alpha, 0f)
            presentation.setMapVisible(false)
            assertEquals(0f, presentation.layoutParams.alpha, 0f)
            presentation.setMapVisible(true)
            assertEquals(1f, presentation.layoutParams.alpha, 0f)
            presentation.setTurnCardGuidance(null)
            assertEquals("Route end/expiry restores the OEM navigation tile", 0f, presentation.layoutParams.alpha, 0f)
        } finally { presentation.dismiss() }
    }

    @Test fun endingSemanticGuidanceDoesNotHideARealMapStream() {
        val presentation = LeapmotorClusterMapOverlay(context, display()) {}
        try {
            presentation.show()
            presentation.setStreamActive(true)
            presentation.setTurnCardGuidance(com.shilapi.xcertplay.hud.ClusterTurnGuidance(2, 0, 100, "Calle"))
            presentation.setTurnCardGuidance(null)
            assertEquals(1f, presentation.layoutParams.alpha, 0f)
            presentation.setStreamActive(false)
            assertEquals(0f, presentation.layoutParams.alpha, 0f)
        } finally { presentation.dismiss() }
    }

    @Test fun aRetiredNavigationWindowCannotResurrectItsOldSemanticInstruction() {
        val presentation = LeapmotorClusterMapOverlay(context, display()) {}
        try {
            presentation.show()
            presentation.setStreamActive(true)
            presentation.setTurnCardGuidance(com.shilapi.xcertplay.hud.ClusterTurnGuidance(2, 0, 100, "Calle"))
            presentation.dismiss()
            presentation.show()
            assertEquals("A retired map stream cannot resurrect a black navigation tile",
                0f, presentation.layoutParams.alpha, 0f)
            val root = ReflectionHelpers.getField<FrameLayout>(presentation, "root")
            assertEquals(android.view.View.GONE, root.getChildAt(1).visibility)
        } finally { presentation.dismiss() }
    }

    @Test fun vehicleFallbackYieldsToGuidanceAndVideoAndExpiresWithoutHostRefresh() {
        val presentation = LeapmotorClusterMapOverlay(context, display()) {}
        try {
            presentation.show()
            val root = ReflectionHelpers.getField<FrameLayout>(presentation, "root")
            val vehicle = root.getChildAt(2)
            presentation.setVehicleSnapshot(com.shilapi.xcertplay.transport.VehicleStatusSnapshot(
                83, null, 12.0, null, null, null, null))
            assertEquals(android.view.View.VISIBLE, vehicle.visibility)
            assertEquals(1f, presentation.layoutParams.alpha, 0f)
            presentation.setTurnCardGuidance(com.shilapi.xcertplay.hud.ClusterTurnGuidance(2, 0, 100, "Calle"))
            assertEquals(android.view.View.GONE, vehicle.visibility)
            presentation.setTurnCardGuidance(null)
            assertEquals(android.view.View.VISIBLE, vehicle.visibility)
            presentation.setStreamActive(true)
            assertEquals(android.view.View.GONE, vehicle.visibility)
            presentation.setStreamActive(false)
            assertEquals(android.view.View.VISIBLE, vehicle.visibility)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
            assertEquals(android.view.View.GONE, vehicle.visibility)
            assertEquals(0f, presentation.layoutParams.alpha, 0f)
        } finally { presentation.dismiss() }
    }

    @Test fun hiddenAndRetiredWindowsDiscardVehiclePublicationsRatherThanResurrectThem() {
        val presentation = LeapmotorClusterMapOverlay(context, display()) {}
        val snapshot = com.shilapi.xcertplay.transport.VehicleStatusSnapshot(160, null, 50.0, null, null, null, null)
        try {
            presentation.show()
            presentation.setVehicleSnapshot(snapshot)
            presentation.setMapVisible(false)
            presentation.setVehicleSnapshot(snapshot)
            presentation.setMapVisible(true)
            assertEquals(0f, presentation.layoutParams.alpha, 0f)
            presentation.setVehicleSnapshot(snapshot)
            presentation.dismiss()
            presentation.setVehicleSnapshot(snapshot)
            presentation.show()
            assertEquals(0f, presentation.layoutParams.alpha, 0f)
            presentation.setVehicleSnapshot(snapshot)
            presentation.setVehicleSnapshot(null)
            assertEquals(0f, presentation.layoutParams.alpha, 0f)
        } finally { presentation.dismiss() }
    }

    @Test fun anUnverifiedC11DisplayCannotCreateAFullScreenFallbackWindow() {
        val presentation = LeapmotorClusterMapOverlay(context, display("local:130")) {}
        try { assertThrows(IllegalStateException::class.java) { presentation.show() } }
        finally { presentation.dismiss() }
    }

    @Test fun overlayPermissionIsRequiredAndDismissIsIdempotent() {
        val presentation = LeapmotorClusterMapOverlay(context, display()) {}
        ShadowSettings.setCanDrawOverlays(false)
        assertThrows(IllegalStateException::class.java) { presentation.show() }
        assertFalse(presentation.showing())
        ShadowSettings.setCanDrawOverlays(true)
        var dismissals = 0
        presentation.setOnDismissListener { dismissals++ }
        presentation.show()
        presentation.dismiss(); presentation.dismiss()
        assertEquals(1, dismissals)
        assertFalse(presentation.showing())
    }

    @Test fun streamUsesTheSquareTileWithoutBydPhysicalDimensionsOrSafeArea() {
        val config = LeapmotorClusterDisplay.streamConfig(CarPlayClusterDisplay.Content.MAP)
        assertEquals(720, config.widthPixels); assertEquals(720, config.heightPixels)
        assertEquals(30, config.fps); assertEquals(0, config.primaryInputDevice)
        assertEquals(CarPlayClusterDisplay.MAP_URL, config.initialUrl)
        assertNull(config.widthPhysicalMm); assertNull(config.heightPhysicalMm)
        assertEquals(config.safeArea!!.left, config.safeArea!!.right)
        assertEquals(config.safeArea!!.top, config.safeArea!!.bottom)
    }

    @Test fun staleBydRoutingPreferencesCannotStartAnAdbClusterRouteOnC11() {
        AirPlayPersistence.saveAdbClusterEnabled(context, true)
        AirPlayPersistence.saveLegacyClusterEnabled(context, true)
        assertFalse(AdbClusterRouter.enabled(context))
    }

    private fun surfaceCallbacks(presentation: LeapmotorClusterMapOverlay): List<SurfaceHolder.Callback> {
        val root = ReflectionHelpers.getField<FrameLayout>(presentation, "root")
        val video = root.getChildAt(0) as SurfaceView
        return (video.holder as org.robolectric.shadows.ShadowSurfaceView.FakeSurfaceHolder).callbacks.toList()
    }

    private fun holder(surface: Surface): SurfaceHolder = mock(SurfaceHolder::class.java).also {
        `when`(it.surface).thenReturn(surface)
    }

    @Test fun staleSurfaceDestructionCannotDetachTheReplacementSurface() {
        val events = mutableListOf<Surface?>()
        val presentation = LeapmotorClusterMapOverlay(context, display(), events::add)
        val first = mock(Surface::class.java)
        val replacement = mock(Surface::class.java)
        try {
            presentation.show()
            val callbacks = surfaceCallbacks(presentation)
            callbacks.forEach { it.surfaceCreated(holder(first)) }
            callbacks.forEach { it.surfaceCreated(holder(replacement)) }
            val beforeDuplicateCreate = events.size
            callbacks.forEach { it.surfaceCreated(holder(replacement)) }
            assertEquals(beforeDuplicateCreate, events.size)
            val beforeLateDestroy = events.size
            callbacks.forEach { it.surfaceDestroyed(holder(first)) }
            assertSame(replacement, presentation.outputSurface)
            assertEquals(beforeLateDestroy, events.size)
            callbacks.forEach { it.surfaceDestroyed(holder(replacement)) }
            assertNull(presentation.outputSurface)
            assertNull(events.last())
        } finally { presentation.dismiss() }
        verify(first, never()).release()
        verify(replacement, never()).release()
    }

    @Test fun dismissedWindowCallbacksCannotAdoptOrDetachAReopenedNavigationWindow() {
        val events = mutableListOf<Surface?>()
        var dismissed = 0
        val presentation = LeapmotorClusterMapOverlay(context, display(), events::add)
        presentation.setOnDismissListener { dismissed++ }
        val old = mock(Surface::class.java)
        val current = mock(Surface::class.java)
        try {
            presentation.show()
            val retiredCallbacks = surfaceCallbacks(presentation)
            retiredCallbacks.forEach { it.surfaceCreated(holder(old)) }
            presentation.dismiss()
            assertEquals(1, dismissed)
            assertNull(presentation.outputSurface)
            presentation.show()
            val callbacks = surfaceCallbacks(presentation)
            callbacks.forEach { it.surfaceCreated(holder(current)) }
            val currentEvents = events.toList()
            retiredCallbacks.forEach {
                it.surfaceCreated(holder(old))
                it.surfaceDestroyed(holder(old))
            }
            assertEquals(currentEvents, events)
            assertSame(current, presentation.outputSurface)
            presentation.dismiss()
            val closedEvents = events.toList()
            callbacks.forEach { it.surfaceCreated(holder(current)); it.surfaceDestroyed(holder(current)) }
            assertEquals(closedEvents, events)
            presentation.dismiss()
            assertEquals(2, dismissed)
        } finally { presentation.dismiss() }
        verify(old, never()).release()
        verify(current, never()).release()
    }

    @Test fun disconnectCallbackSeesAlreadyRetiredWindowAndCannotObserveItsSurface() {
        var presentation: LeapmotorClusterMapOverlay? = null
        var detachCallbacks = 0
        val navigation = LeapmotorClusterMapOverlay(context, display()) { surface ->
            if (surface == null) {
                detachCallbacks++
                assertFalse(presentation!!.showing())
                assertNull(presentation!!.outputSurface)
            }
        }
        presentation = navigation
        navigation.show()
        val surface = mock(Surface::class.java)
        surfaceCallbacks(navigation).forEach { it.surfaceCreated(holder(surface)) }
        navigation.dismiss()
        navigation.dismiss()
        assertEquals(1, detachCallbacks)
        verify(surface, never()).release()
    }

    @Test fun partiallyAddedWindowRetiresItsSurfaceWithoutPublishingADismissalOrRetainingTheAttempt() {
        val windowManager = mock(WindowManager::class.java)
        val visual = object : ContextWrapper(context) {
            override fun createDisplayContext(display: Display): Context = this
            override fun createWindowContext(type: Int, options: Bundle?): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == Context.WINDOW_SERVICE) windowManager else super.getSystemService(name)
        }
        val surface = mock(Surface::class.java)
        val events = mutableListOf<Surface?>()
        var dismissals = 0
        var retiredCallbacks = emptyList<SurfaceHolder.Callback>()
        doAnswer {
            assertNull("Decoder must detach before Android removes its Surface", events.last())
            null
        }.`when`(windowManager).removeViewImmediate(any())
        doAnswer { invocation ->
            val view = invocation.arguments[0] as FrameLayout
            val video = view.getChildAt(0) as SurfaceView
            retiredCallbacks = (video.holder as org.robolectric.shadows.ShadowSurfaceView.FakeSurfaceHolder).callbacks.toList()
            retiredCallbacks.forEach { it.surfaceCreated(holder(surface)) }
            throw SecurityException("Window rejected after surface delivery")
        }.`when`(windowManager).addView(any(), any())
        val presentation = LeapmotorClusterMapOverlay(visual, display(), events::add)
        presentation.setOnDismissListener { dismissals++ }
        assertThrows(SecurityException::class.java) { presentation.show() }
        assertEquals(listOf(surface, null), events)
        assertFalse(presentation.showing())
        assertNull(presentation.outputSurface)
        assertEquals(0, dismissals)
        retiredCallbacks.forEach { it.surfaceCreated(holder(surface)); it.surfaceDestroyed(holder(surface)) }
        assertEquals(listOf(surface, null), events)
        verify(surface, never()).release()
        doNothing().`when`(windowManager).addView(any(), any())
        try {
            presentation.show()
            assertTrue(presentation.showing())
        } finally { presentation.dismiss() }
        assertEquals(1, dismissals)
    }
}
