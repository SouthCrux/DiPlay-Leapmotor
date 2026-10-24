// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.os.Bundle
import android.os.Handler
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.UltraReferenceCenterHost
import com.shilapi.xcertplay.airplay.UltraThemeBinding
import com.shilapi.xcertplay.airplay.UltraThemeDisplay
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.transport.Iap2ThemeAsset
import com.shilapi.xcertplay.vehicle.C11UltraSettings
import com.shilapi.xcertplay.vehicle.VehicleIntegrationPolicy
import java.util.concurrent.ExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaCodecList
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowSettings
import org.json.JSONObject
import java.io.File

/** Exercises the real Activity's routing/configuration without starting its lifecycle or transports. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostReferenceCenterTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var sink: AndroidMediaSink
    private lateinit var host: UltraReferenceCenterHost
    private lateinit var attachment: UltraReferenceCenterHost.Attachment
    private val binding = UltraThemeBinding(Iap2ThemeAsset("44C9FA31EC", 985, "19.0"),
        UltraThemeDisplay("Center_Display", 1920, 720, 157))

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        sink = mock(AndroidMediaSink::class.java)
        host = mock(UltraReferenceCenterHost::class.java)
        attachment = mock(UltraReferenceCenterHost.Attachment::class.java)
        `when`(host.themeBinding).thenReturn(binding)
        set("sink", sink)
        set("airPlayIdentity", AirPlayIdentity.generate())
        set("hevcEnabled", false)
        set("uiScalePercent", 100); set("displayScalePercent", 100)
        AirPlayPersistence.saveUiScalePercent(activity, 100)
        AirPlayPersistence.saveDisplayScalePercent(activity, 100)
        AirPlayPersistence.saveClusterMapEnabled(activity, false)
        ShadowMediaCodecList.reset()
    }

    @After fun tearDown() {
        (get("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (get("teardownExecutor") as ExecutorService).shutdownNow()
        (get("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        ShadowMediaCodecList.reset()
        C11UltraSettings.setEnabled(activity, false)
    }

    private fun installHost() {
        set("referenceCenterHost", host)
        set("referenceCenterAttachment", attachment)
    }

    @Test fun conventionalC11OffersReadOnlyVehicleDataWithoutUltraDisplayNegotiation() {
        enableC11()
        C11UltraSettings.setEnabled(activity, false)
        val ordinary = config()
        assertTrue(ordinary.vehicleStateProtocol)
        assertFalse(ordinary.ultraHandshake)
        assertNull(ordinary.ultraThemeBinding)
        assertEquals(990, ordinary.main.heightPixels)
        assertTrue(ordinary.main.widthPixels < 1920) // Keep the accepted vehicle sidebar.
    }

    @Test fun otherVehicleProfilesDoNotAcquireC11VehicleProtocolByDefault() {
        val info = activity.packageManager.getPackageInfo(activity.packageName,
            android.content.pm.PackageManager.GET_META_DATA)
        info.applicationInfo!!.metaData = Bundle().apply {
            putString(VehicleIntegrationPolicy.METADATA_KEY, VehicleIntegrationPolicy.BYD)
        }
        shadowOf(activity.packageManager).installPackage(info)
        assertFalse(config().vehicleStateProtocol)
    }

    @Test fun textureTrialRoutesOnlyToItsAttachmentAndSelectsTheReferenceBuffer() {
        installHost()
        val texture = mock(SurfaceTexture::class.java)
        val surface = mock(Surface::class.java)
        set("currentSurfaceTexture", texture)

        invoke("attachSurface", Surface::class.java, surface)

        verify(texture).setDefaultBufferSize(1920, 720)
        verify(attachment).updateSurface(surface, 1920, 720)
        verifyNoInteractions(sink)
        assertEquals(true, get("referenceBufferOverride"))
    }

    @Test fun surfaceViewTrialReportsActualBufferDimensionsWhileRequestingSelectedSize() {
        installHost()
        val holder = mock(SurfaceHolder::class.java)
        val view = mock(SurfaceView::class.java)
        val surface = mock(Surface::class.java)
        `when`(view.holder).thenReturn(holder)
        `when`(holder.surfaceFrame).thenReturn(Rect(0, 0, 1200, 600))
        set("fallbackVideoView", view)

        invoke("attachSurface", Surface::class.java, surface)

        val order = inOrder(holder, attachment)
        order.verify(holder).setFixedSize(1920, 720)
        order.verify(attachment).updateSurface(surface, 1200, 600)
        verifyNoInteractions(sink)
    }

    @Test fun ordinaryProjectionRetainsItsExistingMainAndAlternateRouting() {
        val surface = mock(Surface::class.java)
        invoke("attachSurface", Surface::class.java, surface)
        verify(sink).setSurface(110, surface)
        verify(sink).setSurface(111, surface)
        verifyNoInteractions(attachment)

        clearInvocations(sink)
        val cluster = mock(Surface::class.java)
        AirPlayPersistence.saveClusterMapEnabled(activity, true)
        set("clusterSurface", cluster)
        invoke("attachSurface", Surface::class.java, surface)
        verify(sink).setSurface(110, surface)
        verify(sink).setSurface(111, cluster)
        verify(sink, never()).setSurface(111, surface)
    }

    @Test fun selectedConfigIsCenterOnlyAndFallbackRestoresOrdinaryGeometryWithoutChangingOptIn() {
        enableC11()
        C11UltraSettings.setEnabled(activity, true)
        val selectedPreferences = activity.getSharedPreferences("c11_ultra", 0).all.toMap()
        set("referenceFallback", true)
        val ordinary = config()
        assertFalse(ordinary.ultraHandshake)
        assertNull(ordinary.ultraThemeBinding)
        assertEquals((1920 - C11VehicleSidebar.widthPixels(activity.resources, 1920)) / 2 * 2, ordinary.main.widthPixels)
        assertEquals(990, ordinary.main.heightPixels)

        set("referenceFallback", false)
        installHost()
        val selected = config()
        assertTrue(selected.ultraHandshake)
        assertEquals(binding, selected.ultraThemeBinding)
        assertEquals(1920, selected.main.widthPixels)
        assertEquals(720, selected.main.heightPixels)
        assertNull(selected.cluster)
        assertFalse(selected.hevc)

        set("referenceFallback", true)
        val fallback = config()
        assertEquals(ordinary.main, fallback.main)
        assertEquals(ordinary.cluster, fallback.cluster)
        assertFalse(fallback.ultraHandshake)
        assertNull(fallback.ultraThemeBinding)
        assertEquals(selectedPreferences, activity.getSharedPreferences("c11_ultra", 0).all)
        assertTrue(C11UltraSettings.enabled(activity))
        assertEquals(100, AirPlayPersistence.loadUiScalePercent(activity))
        assertEquals(100, AirPlayPersistence.loadDisplayScalePercent(activity))
    }

    @Test @Config(sdk = [32])
    fun referenceCenterRetainsOnlyNavigationWithAConfirmedPhysicalClusterRoute() {
        enableC11()
        C11UltraSettings.setEnabled(activity, true)
        AirPlayPersistence.saveClusterMapEnabled(activity, true)
        installHost()
        val fingerprint = android.os.Build.FINGERPRINT
        val route = File(activity.noBackupFilesDir, LeapmotorClusterDisplay.ROUTE_FILE)
        val id = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi")
        try {
            ShadowBuild.setFingerprint(LeapmotorClusterDisplay.FINGERPRINT)
            ShadowSettings.setCanDrawOverlays(true)
            Settings.Global.putInt(activity.contentResolver, Settings.Global.BOOT_COUNT, 42)
            val display = activity.getSystemService(DisplayManager::class.java).getDisplay(id)
            shadowOf(display).setRotation(0); shadowOf(display).setFlags(0)
            route.parentFile!!.mkdirs()
            route.writeText(JSONObject().put("schemaVersion", 1).put("bootCount", 42)
                .put("fingerprint", LeapmotorClusterDisplay.FINGERPRINT)
                .put("physicalUniqueId", LeapmotorClusterDisplay.UNIQUE_ID)
                .put("logicalDisplayId", id).put("modeId", display.mode.modeId)
                .put("width", 1920).put("height", 720).toString())
            val selected = config()
            assertEquals(1920, selected.main.widthPixels)
            assertEquals(720, selected.main.heightPixels)
            assertEquals(720, selected.cluster!!.widthPixels)
            assertEquals(720, selected.cluster!!.heightPixels)
            assertNotNull(selected.cluster!!.initialUrl)
            assertEquals(selected.cluster, selected.ultraThemeBinding!!.conventionalNavigation)
            assertNull(selected.ultraThemeBinding!!.cluster)
            // A reboot invalidates the measured route; no virtual/passenger replacement is offered.
            Settings.Global.putInt(activity.contentResolver, Settings.Global.BOOT_COUNT, 43)
            assertNull(config().cluster)
        } finally {
            route.delete(); ShadowDisplayManager.removeDisplay(id); ShadowBuild.setFingerprint(fingerprint)
        }
    }

    @Test fun textureOwnerDetachesTheTrialBeforeReleasingItsOwnedSurface() {
        installHost()
        val surface = mock(Surface::class.java)
        @Suppress("UNCHECKED_CAST")
        val owner = get("videoSurfaceOwner") as CarPlayVideoSurfaceOwner<Surface>
        owner.replace(surface, releaseOnDetach = true)
        owner.clear()

        val order = inOrder(attachment, sink, surface)
        order.verify(attachment).detach(surface)
        order.verify(sink).clearSurface(110, surface)
        order.verify(sink).clearSurface(111, surface)
        order.verify(surface).release()
        assertNull(owner.current)
    }

    @Test fun unbindingClosesOnlyTheActivityAttachmentAndLeavesControllerHostAlive() {
        installHost()
        val controller = mock(CarPlayController::class.java)
        `when`(controller.referenceCenterHost).thenReturn(host)
        set("controller", controller)
        set("referencePreparationGeneration", 7)
        invoke("unbindReferenceHost")
        verify(attachment).close()
        verify(host, never()).close()
        assertNull(get("referenceCenterAttachment"))
        assertNull(get("referenceCenterHost"))
        assertNull(get("referencePreparationGeneration"))
    }

    @Test fun unbindingAPreparedHostWithoutControllerOwnershipClosesItOffTheUiThread() {
        installHost()
        val closed = CountDownLatch(1)
        val closedOn = AtomicReference<Thread>()
        doAnswer { closedOn.set(Thread.currentThread()); closed.countDown(); null }.`when`(host).close()

        invoke("unbindReferenceHost")

        assertTrue("Orphan prepared resources must be retired", closed.await(3, TimeUnit.SECONDS))
        assertNotSame(Thread.currentThread(), closedOn.get())
        verify(attachment).close()
        verify(host).close()
        assertNull(get("referenceCenterAttachment"))
        assertNull(get("referenceCenterHost"))
    }

    @Test fun rebindingRetiresAnOrphanPreparedHostAndAttachesTheReplacement() {
        installHost()
        val closed = CountDownLatch(1)
        doAnswer { closed.countDown(); null }.`when`(host).close()
        val replacement = mock(UltraReferenceCenterHost::class.java)
        val next = mock(UltraReferenceCenterHost.Attachment::class.java)
        `when`(replacement.attachHost(org.mockito.ArgumentMatchers.any<() -> Unit>() ?: {})).thenReturn(next)

        invoke("bindReferenceHost", UltraReferenceCenterHost::class.java, replacement)

        assertTrue(closed.await(3, TimeUnit.SECONDS))
        verify(attachment).close()
        verify(host).close()
        verify(next).updateResumed(false)
        assertSame(replacement, get("referenceCenterHost"))
        assertSame(next, get("referenceCenterAttachment"))
    }

    private fun enableC11() {
        val info = activity.packageManager.getPackageInfo(activity.packageName,
            android.content.pm.PackageManager.GET_META_DATA)
        info.applicationInfo!!.metaData = Bundle().apply {
            putString(VehicleIntegrationPolicy.METADATA_KEY, VehicleIntegrationPolicy.LEAPMOTOR_C11)
        }
        shadowOf(activity.packageManager).installPackage(info)
    }

    private fun config(): AirPlayConfig {
        val type = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = type.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1920, 990)
        return invoke("createAirPlayConfig", type, size) as AirPlayConfig
    }
    private fun invoke(name: String) = activity.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)
    private fun invoke(name: String, parameter: Class<*>, value: Any) = activity.javaClass.getDeclaredMethod(name, parameter)
        .apply { isAccessible = true }.invoke(activity, value)
    private fun get(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun set(name: String, value: Any?) = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(activity, value)
}
