package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.media.TimedVideoFrame
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class UltraCenterScenePolicyTest {
    private val off = UltraCenterScenePolicy.Snapshot(false, UltraVehicleSceneState.NONE)
    private val on = UltraCenterScenePolicy.Snapshot(true, UltraVehicleSceneState.ACCESSORY_MODE)

    @Test fun visibleHostCannotActivateWithoutAnExplicitRequest() {
        val policy = UltraCenterScenePolicy()
        assertEquals(off, policy.takePending())
        policy.updateHostVisibility(resumed = true, surfaceReady = true)
        assertNull(policy.takePending())
        policy.requestAccessoryScene(true)
        assertEquals(on, policy.takePending())
        assertTrue(policy.allowsPublication())
        policy.requestAccessoryScene(false)
        assertFalse(policy.allowsPublication())
        assertEquals(off, policy.takePending())
    }

    @Test fun requestWaitsForBothHostAndSurfaceAndSuspendsWithoutLosingIntent() {
        val policy = UltraCenterScenePolicy()
        policy.takePending()
        policy.requestAccessoryScene(true)
        policy.updateHostVisibility(resumed = true, surfaceReady = false)
        assertNull(policy.takePending())
        policy.updateHostVisibility(resumed = false, surfaceReady = true)
        assertNull(policy.takePending())
        policy.updateHostVisibility(resumed = true, surfaceReady = true)
        assertEquals(on, policy.takePending())
        policy.updateHostVisibility(resumed = false, surfaceReady = true)
        assertEquals(off, policy.takePending())
        policy.updateHostVisibility(resumed = true, surfaceReady = true)
        assertEquals(on, policy.takePending())
        policy.updateHostVisibility(resumed = true, surfaceReady = false)
        assertEquals(off, policy.takePending())
        policy.requestAccessoryScene(false)
        policy.updateHostVisibility(resumed = true, surfaceReady = true)
        assertNull(policy.takePending())
    }

    @Test fun coalescedForegroundAndSceneRemainOneSnapshot() {
        val policy = UltraCenterScenePolicy()
        policy.takePending()
        policy.requestAccessoryScene(true)
        repeat(10_000) {
            policy.updateHostVisibility(resumed = true, surfaceReady = true)
            policy.updateHostVisibility(resumed = false, surfaceReady = false)
        }
        assertEquals(off, policy.takePending())
        assertNull(policy.takePending())
    }

    @Test fun closedConnectionCannotReactivateAndReplacementHasNoInheritedRequest() {
        val old = UltraCenterScenePolicy()
        old.requestAccessoryScene(true)
        old.updateHostVisibility(resumed = true, surfaceReady = true)
        old.close()
        old.requestAccessoryScene(true)
        old.updateHostVisibility(resumed = true, surfaceReady = true)
        assertNull(old.takePending())
        val replacement = UltraCenterScenePolicy()
        replacement.updateHostVisibility(resumed = true, surfaceReady = true)
        assertEquals(off, replacement.takePending())
    }

    @Test fun pauseResumeCannotPublishAnOutputFromThePreviousActivation() {
        val policy = UltraCenterScenePolicy()
        policy.requestAccessoryScene(true)
        policy.updateHostVisibility(resumed = true, surfaceReady = true)
        val previous = checkNotNull(policy.publicationToken())
        assertTrue(policy.allowsPublication(previous))
        policy.updateHostVisibility(resumed = false, surfaceReady = true)
        assertFalse(policy.allowsPublication(previous))
        policy.updateHostVisibility(resumed = true, surfaceReady = true)
        assertFalse(policy.allowsPublication(previous))
        val current = checkNotNull(policy.publicationToken())
        assertTrue(policy.allowsPublication(current))
        policy.close()
        assertFalse(policy.allowsPublication(current))
        assertNull(policy.publicationToken())
    }

    private fun explicitFile(name: String): File {
        val path = System.getenv(name)
        assumeTrue("Explicit native test input required: $name", path != null)
        return File(path!!).absoluteFile.also { require(it.exists()) }
    }

    @Test fun nativePolicyAppliesPausesAndWithdrawalWithoutAllowingARawModeWriter() {
        val library = explicitFile("DIPLAY_ULTRA_JNI_LIBRARY")
        val bridge = explicitFile("DIPLAY_ULTRA_BRIDGE_SOURCE")
        val root = Files.createTempDirectory("diplay-ultra-policy").toFile()
        val entry = """
            Offscreen.configureOutput("fixture",40,30)
            Offscreen.configureSceneLifecycle()
            Offscreen.startProtocolForResearch()
            local task=CP.DPLuaTask.create()
            task:run(function()while true do
                CP.sendMessage(CP.createPlist({foreground=CP.isForegrounded,state=CP.getVehicleData({1})[1]}))
                coroutine.yield()
            end end)
            return "initialized"
        """.trimIndent()
        try {
            val runtime = UltraNativeRuntime.open(root, bridge.readBytes(), entry.toByteArray(), loadLibrary = { System.load(library.path) })
            val session = UltraComposedPanelSession<TimedVideoFrame>(runtime, "iPhoneVideo", UltraSceneInputMode.REFERENCE_CENTER_ACCESSORY)
            session.use {
                fun expect(foreground: Boolean, state: Long) {
                    assertEquals(mapOf("foreground" to foreground, "state" to state), BoundedPlist.decode(session.poll().single()))
                }
                expect(false, 0)
                session.updateHostVisibility(resumed = true, surfaceReady = true)
                expect(false, 0)
                session.requestAccessoryScene(true)
                expect(true, 4)
                session.updateHostVisibility(resumed = false, surfaceReady = true)
                expect(false, 0)
                session.updateHostVisibility(resumed = true, surfaceReady = true)
                expect(true, 4)
                session.updateHostVisibility(resumed = true, surfaceReady = false)
                expect(false, 0)
                session.updateHostVisibility(resumed = true, surfaceReady = true)
                expect(true, 4)
                session.requestAccessoryScene(false)
                expect(false, 0)
                assertThrows(IllegalStateException::class.java) { session.updateVehicleScene(UltraVehicleSceneState.DRIVE) }
                expect(false, 0)
            }
            session.requestAccessoryScene(true)
            assertThrows(IllegalStateException::class.java) { session.poll() }
        } finally { root.delete() }
    }

    @Test fun originalCenterRequestsSelectedLayoutFromTheExplicitPolicy() {
        val library = explicitFile("DIPLAY_ULTRA_JNI_LIBRARY")
        val bridge = explicitFile("DIPLAY_ULTRA_BRIDGE_SOURCE")
        val resources = explicitFile("DIPLAY_ULTRA_TEST_RESOURCES")
        val runtime = UltraNativeRuntime.open(resources, bridge.readBytes(), File(bridge.parentFile, "center_wallpaper_entry.luau").readBytes(),
            researchOverride = true, loadLibrary = { System.load(library.path) })
        UltraComposedPanelSession<TimedVideoFrame>(runtime, "iPhoneVideo", UltraSceneInputMode.REFERENCE_CENTER_ACCESSORY).use { session ->
            fun decode(messages: List<ByteArray>) = messages.map { checkNotNull(BoundedPlist.decode(it)) }
            val reset = decode(session.poll()).first { it["type"] == 0L }
            val replies = decode(session.receive(BplistCodec.encode(mapOf(
                "type" to 1L, "ssn" to reset.getValue("ssn"), "psn" to 1L,
                "asn" to reset.getValue("psn"), "version" to 2L, "id" to "fixture-peer",
            )))).toMutableList()
            repeat(8) { replies += decode(session.poll()) }
            session.requestAccessoryScene(true)
            session.updateHostVisibility(resumed = true, surfaceReady = true)
            repeat(10) { replies += decode(session.poll()) }
            val commands = replies.filter { it["type"] == 2L }.mapNotNull {
                (it["data"] as? ByteArray)?.let(BoundedPlist::decode)
            }
            assertTrue(commands.any { it["type"] == 5L && (it["data"] as? Map<*, *>)?.get("layoutID") == "9E19BC4E-5251-5821-A0BA-26479A79F722" })
            session.requestAccessoryScene(false)
            repeat(3) { session.poll() }
        }
    }
}
