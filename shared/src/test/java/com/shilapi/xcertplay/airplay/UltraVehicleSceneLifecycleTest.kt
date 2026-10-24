package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.media.TimedVideoFrame
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class UltraVehicleSceneLifecycleTest {
    @Test fun missingSceneIsDifferentFromExplicitNone() {
        val input = UltraVehicleSceneLifecycle()
        assertNull(checkNotNull(input.takePending()).state)
        assertNull(input.takePending())
        input.update(UltraVehicleSceneState.NONE)
        assertEquals(UltraVehicleSceneState.NONE, checkNotNull(input.takePending()).state)
        input.update(null)
        assertNull(checkNotNull(input.takePending()).state)
    }

    @Test fun sourceLossReplacesPendingModeAndRetirementDiscardsLateUpdates() {
        val input = UltraVehicleSceneLifecycle()
        input.takePending()
        input.update(UltraVehicleSceneState.DRIVE)
        input.update(null)
        assertNull(checkNotNull(input.takePending()).state)
        input.update(UltraVehicleSceneState.ACCESSORY_MODE)
        input.close()
        input.update(UltraVehicleSceneState.DRIVE)
        assertNull(input.takePending())
        val replacement = UltraVehicleSceneLifecycle()
        assertNull(checkNotNull(replacement.takePending()).state)
    }

    private fun withRuntime(configure: Boolean = true, block: (UltraNativeRuntime) -> Unit) {
        val library = System.getenv("DIPLAY_ULTRA_JNI_LIBRARY")
        val bridge = System.getenv("DIPLAY_ULTRA_BRIDGE_SOURCE")
        assumeTrue(library != null && bridge != null)
        val root = Files.createTempDirectory("diplay-ultra-scene").toFile()
        val entry = """
            Offscreen.configureOutput("fixture",40,30)
            ${if (configure) "Offscreen.configureSceneLifecycle()" else ""}
            Offscreen.startProtocolForResearch()
            local task=CP.DPLuaTask.create()
            task:run(function()while true do
                local state=CP.getVehicleData({1})[1]
                local available=state~=nil and state~=CP.INVALIDVALUE
                CP.sendMessage(CP.createPlist({available=available,state=if available then state else -1,
                    foreground=CP.isForegrounded,declared=CP.getVehicleDataConfig().uiControl~=nil}))
                coroutine.yield()
            end end)
            return "initialized"
        """.trimIndent()
        try {
            UltraNativeRuntime.open(root, File(bridge!!).readBytes(), entry.toByteArray(),
                loadLibrary = { System.load(library!!) }).use(block)
        } finally { root.delete() }
    }

    @Test fun nativeSceneStartsUnavailableAndDoesNotFollowHostVisibility() = withRuntime { runtime ->
        val session = UltraComposedPanelSession<TimedVideoFrame>(runtime, "phone-video", UltraSceneInputMode.EXPLICIT_SERVICE)
        session.use {
            fun poll() = checkNotNull(BoundedPlist.decode(session.poll().single()))
            assertEquals(mapOf("available" to false, "state" to -1L, "foreground" to false, "declared" to true), poll())
            session.updateHostVisibility(resumed = true, surfaceReady = true)
            assertEquals(mapOf("available" to false, "state" to -1L, "foreground" to true, "declared" to true), poll())
            for (state in UltraVehicleSceneState.entries) {
                session.updateVehicleScene(state)
                val observed = poll()
                assertEquals(true, observed["available"])
                assertEquals(state.wireValue.toLong(), observed["state"])
            }
            session.updateVehicleScene(null)
            val invalidated = poll()
            assertEquals(false, invalidated["available"])
            assertEquals(-1L, invalidated["state"])
            assertEquals(true, invalidated["foreground"])
        }
        session.updateVehicleScene(UltraVehicleSceneState.DRIVE)
        assertThrows(IllegalStateException::class.java) { session.poll() }
    }

    @Test fun nativeSceneUpdateRequiresAnExplicitlyDeclaredService() = withRuntime(configure = false) { runtime ->
        assertThrows(IllegalStateException::class.java) { runtime.request(8, byteArrayOf(2)) }
        assertThrows(IllegalStateException::class.java) { runtime.request(6) }
    }

    @Test fun invalidNativeSceneValuesRetireTheVmRatherThanBecomingAValidMode() {
        for (value in listOf(5, 254)) withRuntime { runtime ->
            assertThrows(IllegalStateException::class.java) { runtime.request(8, byteArrayOf(value.toByte())) }
            assertThrows(IllegalStateException::class.java) { runtime.request(6) }
        }
    }
}
