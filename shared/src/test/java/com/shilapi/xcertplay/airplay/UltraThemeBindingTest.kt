package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.transport.Iap2ThemeAsset
import java.io.File
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UltraThemeBindingTest {
    private val theme = UltraThemeBinding(Iap2ThemeAsset("44C9FA31EC", 985, "19.0"),
        UltraThemeDisplay("Center_Display", 1920, 720, 157),
        UltraThemeDisplay("Cluster_Display", 1920, 720, 157))
    private fun base() = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0",
        AirPlayDisplayConfig(2560, 1176, viewAreas = listOf(AirPlayViewArea(2560, 1176))),
        cluster = AirPlayDisplayConfig(1280, 720, initialUrl = "maps:/car/instrumentcluster"), ultraHandshake = true)

    @Test fun resourceNamesBindStreamsPanelsPluginsAndHidWithoutSyntheticPanels() {
        val cfg = theme.configure(base())
        val info = AirPlayInfoPlist.build(cfg)
        val displays = (info["displays"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("Center_Display", "Cluster_Display"), displays.map { it["uuid"] })
        displays.forEach { assertEquals(1920, it["widthPixels"]); assertEquals(720, it["heightPixels"]) }
        assertNull(cfg.main.viewAreas); assertNull(cfg.cluster?.initialUrl)
        val panels = (info["displayPanels"] as List<*>).map { it as Map<*, *> }
        assertEquals(displays.map { it["uuid"] }, panels.map { it["uid"] })
        assertFalse(panels.first().containsKey("displayPlugins"))
        val plugin = (panels.last()["displayPlugins"] as List<*>).single() as Map<*, *>
        assertEquals("Cluster_Display", plugin["uid"])
        assertEquals(listOf("Cluster_Display"), plugin["videoStreams"])
        panels.forEach {
            val stream = (it["initialVideoStreams"] as List<*>).single() as Map<*, *>
            assertEquals(it["uid"], stream["uuid"])
        }
        (info["hidDevices"] as List<*>).forEach { assertEquals("Center_Display", (it as Map<*, *>)["displayUUID"]) }
        assertEquals(setOf("Center_Display", "Cluster_Display"), UltraHandshake.panelIds(cfg))
        val area = AirPlayInfoPlist.viewAreaCommand(0, 1, AirPlayInfoPlist.mainDisplayUuid(cfg))
        assertEquals("Center_Display", (area["params"] as Map<*, *>)["uuid"])
        assertFalse(info.containsKey("vehicleStateProtocolInfo"))
    }

    @Test fun bindingCannotBeDeclaredWithDifferentOrMissingCanvasGeometry() {
        val cfg = theme.configure(base())
        assertThrows(IllegalArgumentException::class.java) { cfg.copy(main = AirPlayDisplayConfig(2560, 1176)) }
        assertThrows(IllegalArgumentException::class.java) { cfg.copy(cluster = null) }
        assertThrows(IllegalArgumentException::class.java) { theme.copy(cluster = theme.main) }
        assertThrows(IllegalArgumentException::class.java) { UltraThemeDisplay("bad\u0000name", 1920, 720, 157) }
        assertThrows(IllegalArgumentException::class.java) { UltraThemeDisplay("valid", 0, 720, 157) }
    }

    @Test fun explicitlySelectedCenterDoesNotAdvertiseAnUnimplementedClusterOrSyntheticPanel() {
        val selected = theme.copy(cluster = null)
        val baseline = base()
        val cfg = selected.configure(baseline)
        assertNull(cfg.cluster)
        assertNotNull(baseline.cluster)
        assertEquals(2560, baseline.main.widthPixels)
        val info = AirPlayInfoPlist.build(cfg)
        val display = (info["displays"] as List<*>).single() as Map<*, *>
        assertEquals(110, display["type"])
        assertEquals("Center_Display", display["uuid"])
        val panel = (info["displayPanels"] as List<*>).single() as Map<*, *>
        assertEquals("Center_Display", panel["uid"])
        assertEquals(emptyList<String>(), panel["properties"])
        assertFalse(panel.containsKey("displayPlugins"))
        assertEquals(setOf("Center_Display"), UltraHandshake.panelIds(cfg))
        (info["hidDevices"] as List<*>).forEach { assertEquals("Center_Display", (it as Map<*, *>)["displayUUID"]) }
        assertThrows(IllegalArgumentException::class.java) { cfg.copy(cluster = baseline.cluster) }
        assertThrows(IllegalArgumentException::class.java) { cfg.copy(main = baseline.main) }
    }

    @Test fun disabledProbeUsesConventionalIdentitiesEvenWithRetainedBinding() {
        val cfg = theme.configure(base()).copy(ultraHandshake = false)
        val info = AirPlayInfoPlist.build(cfg)
        assertFalse(info.containsKey("displayPanels")); assertFalse(info.containsKey("uiSyncInfo"))
        assertEquals(AirPlayInfoPlist.MAIN_UUID, ((info["displays"] as List<*>).first() as Map<*, *>)["uuid"])
        assertEquals(AirPlayInfoPlist.ALT_UUID, AirPlayInfoPlist.altDisplayUuid(cfg))
    }

    @Test fun explicitCenterCompositionDeclaresOnlyItsImplementedPluginAndVideoInput() {
        val selected = theme.copy(cluster = null, composeMainDisplay = true)
        val cfg = selected.configure(base())
        val info = AirPlayInfoPlist.build(cfg)
        val panel = (info["displayPanels"] as List<*>).single() as Map<*, *>
        val plugin = (panel["displayPlugins"] as List<*>).single() as Map<*, *>
        assertEquals("Center_Display", panel["uid"])
        assertEquals("Center_Display", plugin["uid"])
        assertEquals(listOf("Center_Display"), plugin["videoStreams"])
        assertEquals(emptyList<String>(), panel["properties"])
        assertEquals(setOf("Center_Display"), UltraHandshake.panelIds(cfg))
        assertNull(cfg.cluster)
        val video = (panel["initialVideoStreams"] as List<*>).single() as Map<*, *>
        assertEquals("Center_Display", video["uuid"])
        val disabled = AirPlayInfoPlist.build(cfg.copy(ultraHandshake = false))
        assertFalse(disabled.containsKey("displayPanels"))
        assertEquals(AirPlayInfoPlist.MAIN_UUID, ((disabled["displays"] as List<*>).single() as Map<*, *>)["uuid"])
    }

    @Test fun conventionalNavigationKeepsItsOwnGeometryAndUrlWithoutClaimingThemedInstruments() {
        val navigation = AirPlayDisplayConfig(720, 720, fps = 30, primaryInputDevice = 0,
            features = 0, initialUrl = CarPlayClusterDisplay.MAP_URL,
            safeArea = AirPlayInsets(58, 58, 58, 58), safeAreaDrawOutside = true)
        val binding = theme.copy(cluster = null, composeMainDisplay = true)
            .withConventionalNavigation(navigation)
        val cfg = binding.configure(base())
        assertSame(navigation, cfg.cluster)
        assertTrue(cfg.hasConventionalClusterNavigation)
        assertTrue(binding.matches(cfg))
        val info = AirPlayInfoPlist.build(cfg)
        val displays = (info["displays"] as List<*>).map { it as Map<*, *> }
        val alternate = displays.single { it["type"] == 111 }
        assertEquals(AirPlayInfoPlist.ALT_UUID, alternate["uuid"])
        assertEquals(720, alternate["widthPixels"])
        assertEquals(720, alternate["heightPixels"])
        assertEquals(CarPlayClusterDisplay.MAP_URL, alternate["initialURL"])
        assertEquals(0, alternate["primaryInputDevice"])
        val panels = (info["displayPanels"] as List<*>).map { it as Map<*, *> }
        val center = panels.single { it["uid"] == "Center_Display" }
        assertTrue(center.containsKey("displayPlugins"))
        val ordinary = panels.single { it["uid"] == AirPlayInfoPlist.ALT_UUID }
        assertFalse(ordinary.containsKey("displayPlugins"))
        assertEquals(emptyList<String>(), ordinary["properties"])
        assertEquals(AirPlayInfoPlist.ALT_UUID,
            ((ordinary["initialVideoStreams"] as List<*>).single() as Map<*, *>)["uuid"])
        // Only this destination has a scripted protocol implementation. The navigation video must
        // never be dispatched to the center's single-panel native runtime.
        assertEquals(setOf("Center_Display"), UltraHandshake.panelIds(cfg))
        assertFalse(cfg.copy(ultraHandshake = false).hasConventionalClusterNavigation)
    }

    @Test fun conventionalNavigationCannotBeCombinedWithThemedClusterOrSilentlyReconfigured() {
        val navigation = AirPlayDisplayConfig(720, 720, initialUrl = CarPlayClusterDisplay.MAP_URL)
        assertThrows(IllegalArgumentException::class.java) { theme.withConventionalNavigation(navigation) }
        val binding = theme.copy(cluster = null).withConventionalNavigation(navigation)
        val cfg = binding.configure(base())
        assertThrows(IllegalArgumentException::class.java) {
            cfg.copy(cluster = navigation.copy(initialUrl = CarPlayClusterDisplay.Content.TURN_CARD.url))
        }
        assertThrows(IllegalArgumentException::class.java) { cfg.copy(cluster = null) }
        val centerOnly = binding.withConventionalNavigation(null).configure(base())
        assertNull(centerOnly.cluster)
        assertFalse(centerOnly.hasConventionalClusterNavigation)
        assertEquals(setOf("Center_Display"), UltraHandshake.panelIds(centerOnly))
    }

    @Test fun probeAlternateVideoCannotReachPhysicalSinkButMainVideoCan() {
        val events = mutableListOf<Int>()
        val physical = object : MediaSink { override fun onVideoFrame(type: Int, naluBytes: ByteArray) { events += type } }
        val engine = CarPlayMediaEngine(physical)
        val cfg = theme.configure(base())
        AirPlaySession(Socket(), cfg, AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, engine).use { session ->
            assertEquals("Cluster_Display", session.clusterDisplayUuid)
            assertFalse(session.restoreClusterUrl("maps:/car/instrumentcluster", false))
            assertFalse(session.setClusterUrl("maps:/car/instrumentcluster", true))
            assertFalse(session.setClusterUiShown(true))
            assertFalse(session.changeMapZoomLevel(true))
            engine.screenSink(session, 111).onVideoFrame(111, byteArrayOf(1))
            assertTrue(events.isEmpty())
            engine.screenSink(session, 110).onVideoFrame(110, byteArrayOf(1))
            assertEquals(listOf(110), events)
        }
    }

    @Test fun conventionalNavigationRemainsIndependentOfTheCenterProtocolOwner() {
        val center = theme.copy(cluster = null, composeMainDisplay = true)
        val navigation = AirPlayDisplayConfig(720, 720, initialUrl = "maps:/car/instrumentcluster/map")
        val cfg = center.withConventionalNavigation(navigation).configure(base())
        val events = mutableListOf<Int>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) { events += type }
        })
        AirPlaySession(Socket(), cfg, AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, engine).use { session ->
            assertEquals(AirPlayInfoPlist.ALT_UUID, session.clusterDisplayUuid)
            assertFalse(session.suppressesConventionalCluster)
            assertTrue(session.restoreClusterUrl(navigation.initialUrl!!, false))
            assertTrue(session.hasReferenceCenterBinding(center))
            assertFalse(session.hasThemeBinding(center)) // Complete connection binding includes navigation.
            assertFalse(session.hasReferenceCenterBinding(center.copy(composeMainDisplay = false)))
            assertFalse(session.hasReferenceCenterBinding(center.copy(main = center.main.copy(width = 1280))))
            assertFalse(session.hasReferenceCenterBinding(center.copy(asset = center.asset.copy(version = 986))))
            engine.screenSink(session, 111).onVideoFrame(111, byteArrayOf(1))
            assertEquals(listOf(111), events)
        }
        AirPlaySession(Socket(), cfg.copy(ultraHandshake = false), AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, engine).use { assertFalse(it.hasReferenceCenterBinding(center)) }
    }

    @Test fun exportsBothNegotiationsForComparisonWithTheExternalPhonePackage() {
        val directory = File("build/reports/ultra-theme").apply { mkdirs() }
        for ((name, cfg) in listOf("descriptor-only" to base(), "aligned" to theme.configure(base()),
            "center-only" to theme.copy(cluster = null).configure(base()),
            "center-composed" to theme.copy(cluster = null, composeMainDisplay = true).configure(base()))) {
            val record = mapOf("asset" to mapOf("identifier" to theme.asset.identifier,
                "version" to theme.asset.version, "sdkVersion" to theme.asset.sdkVersion),
                "info" to AirPlayInfoPlist.build(cfg))
            File(directory, "$name.plist").writeBytes(BplistCodec.encode(record))
        }
    }
}
