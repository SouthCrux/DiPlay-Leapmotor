package com.shilapi.xcertplay.airplay

import android.graphics.Bitmap
import com.shilapi.xcertplay.media.TimedVideoFrame
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Actual JNI transactions; pixels are owned fake leases, not a mock of the frame ABI. */
class UltraLocalSettingsFramesTest {
    @Test fun unavailableSceneCancelsPendingInputsWithoutClosingTheConnectionOrPublishingAnOutput() = withRuntime { runtime ->
        val firstPhone = Frame(100, 12_000_000_000)
        val firstSettings = Settings(1, 20_000_000_000)
        var invalidations = 0
        UltraFrameCoordinator<Frame>(runtime, "phone-video").use { frames ->
            assertNull(frames.composeWithSettings(firstPhone, firstSettings,
                { _, _, _, _, _ -> throw UltraSceneUnavailableException() }, { _: String -> error("No output exists") },
                { invalidations++ }))
            assertEquals(1, firstPhone.closes); assertEquals(1, firstSettings.closes)
            assertEquals(1, invalidations)
            val nextPhone = Frame(101, 12_100_000_000)
            val nextSettings = Settings(2, 21_000_000_000)
            assertEquals("ready", frames.composeWithSettings(nextPhone, nextSettings,
                { _, _, previousPhone, offeredSettings, previousSettings ->
                    assertNull(previousPhone); assertNull(previousSettings)
                    assertSame(nextSettings, offeredSettings)
                    "ready"
                }, {}))
        }
    }

    private open class Frame(override val token: Long, override val senderNanos: Long) : TimedVideoFrame {
        var closes = 0
        override fun close() { closes++ }
    }
    private class Settings(token: Long, pts: Long) : Frame(token, pts), UltraLocalSettingsFrame {
        override val bitmap: Bitmap get() = error("Frame coordination must not read pixels")
    }
    private class Source(private val pending: MutableList<Settings>) : UltraLocalSettingsFrames {
        var calls = 0
        var invalidations = 0
        var closes = 0
        val phoneTimes = mutableListOf<Long>()
        override fun nextFrame(phoneSenderNanos: Long): UltraLocalSettingsFrame? {
            calls++; phoneTimes += phoneSenderNanos
            return if (pending.isEmpty()) null else pending.removeAt(0)
        }
        override fun invalidate() { invalidations++ }
        override fun close() { closes++ }
    }

    private fun withRuntime(settings: Boolean = true, block: (UltraNativeRuntime) -> Unit) {
        val bridge = System.getenv("DIPLAY_ULTRA_BRIDGE_SOURCE")
        val library = System.getenv("DIPLAY_ULTRA_JNI_LIBRARY")
        assumeTrue("Explicit JNI fixture required", bridge != null && library != null)
        val directory = Files.createTempDirectory("diplay-local-settings-frames").toFile()
        try {
            File(directory, "APL").mkdir()
            File(directory, "APL/json.lua").writeText("""
                return {encode=function(v)
                    return tostring(CP.PTS)..":"..tostring(v.scene.children[1].video.token or 0)..":"..
                        tostring(v.scene.children[2] and v.scene.children[2].video.token or 0)
                end}
            """.trimIndent())
            val entry = """
                Offscreen.configureOutput("fixture",40,30)
                Offscreen.configureSceneLifecycle()
                Offscreen.configureVideoInput("phone-video",4,4,1/30)
                ${if (settings) "Offscreen.configureVideoInput(\"LeapmotorVehicleSettings\",1650,720,1/30,1)" else ""}
                local root=CP.DPLayer.create();root.frame=CP.DPRect(0,0,40,30)
                local phone=CP.DPVideoLayer.create("phone-video");phone.frame=CP.DPRect(0,0,20,30)
                root:addSublayer(phone);phone:subscribe()
                local localVideo=nil
                ${if (settings) "localVideo=CP.DPVideoLayer.create(\"LeapmotorVehicleSettings\");localVideo.frame=CP.DPRect(20,0,20,30);root:addSublayer(localVideo);localVideo:subscribe()" else ""}
                CP.setRootLayer("fixture",root)
                Offscreen.startProtocolForResearch()
                local task=CP.DPLuaTask.create()
                task:run(function()while true do
                    if CP.status() then
                        local command=assert(CP.parsePlist(CP.receiveMessage()))
                        if command.action=="deferPhone" then phone:deferNextPTS()
                        elseif command.action=="deferSettings" then localVideo:deferNextPTS()
                        elseif command.action=="flushSettings" then localVideo:flush()
                        else error("fixture command") end
                    end
                    coroutine.yield()
                end end)
                return "initialized"
            """.trimIndent().toByteArray()
            // Native initialization sandboxes global tables before executing the entrypoint.
            // Install fault injection in this fixture's bridge, without changing the real bridge.
            val fixtureBridge = File(bridge!!).readText() + """

                local frameEvent=Offscreen.videoFrame
                Offscreen.videoFrame=function(bytes)
                    local message=assert(CP.parsePlist(bytes))
                    if message.token==999 and message.action=="commit" and message.input=="LeapmotorVehicleSettings" then
                        error("fixture commit failure")
                    end
                    return frameEvent(bytes)
                end
            """.trimIndent()
            UltraNativeRuntime.open(directory, fixtureBridge.toByteArray(), entry,
                loadLibrary = { System.load(library!!) }).use(block)
        } finally { directory.deleteRecursively() }
    }

    private fun command(runtime: UltraNativeRuntime, action: String) {
        runtime.request(1, BplistCodec.encode(mapOf("action" to action)))
    }
    private fun localState(runtime: UltraNativeRuntime) = runtime.request(7, BplistCodec.encode(mapOf(
        "action" to "state", "input" to UltraLocalSettingsInput.IDENTIFIER))) as Map<*, *>

    @Test fun bothInputsShareOneSnapshotButKeepIndependentTokensAndPresentationClocks() = withRuntime { runtime ->
        val phone = Frame(100, 12_000_000_000)
        val local = Settings(1, 900_000_000_000)
        UltraFrameCoordinator<Frame>(runtime, "phone-video").use { frames ->
            val output = frames.composeWithSettings(phone, local, { bytes, offered, current, newLocal, oldLocal ->
                assertSame(phone, offered); assertNull(current)
                assertSame(local, newLocal); assertNull(oldLocal)
                assertEquals(0, local.closes)
                String(bytes)
            }, {})
            assertEquals(listOf("12", "100", "1"), output!!.split(':').map { it.removeSuffix(".0") })
            val next = Frame(101, 12_100_000_000)
            frames.composeWithSettings(next, null, { _, _, oldPhone, newLocal, oldLocal ->
                assertSame(phone, oldPhone); assertNull(newLocal); assertSame(local, oldLocal)
                "next"
            }, {})
            assertEquals(1, phone.closes); assertEquals(0, local.closes)
        }
        assertEquals(1, local.closes)
    }

    @Test fun localDeferralKeepsThePreviousPixelsAndInvalidatesTheDiscardedUpdate() = withRuntime { runtime ->
        val original = Settings(1, 20_000_000_000)
        val deferred = Settings(2, 21_000_000_000)
        var invalidations = 0
        UltraFrameCoordinator<Frame>(runtime, "phone-video").use { frames ->
            frames.composeWithSettings(Frame(100, 12_000_000_000), original, { _, _, _, _, _ -> "first" }, {})
            command(runtime, "deferSettings")
            frames.composeWithSettings(Frame(101, 12_100_000_000), deferred, { _, _, _, offered, current ->
                assertNull(offered); assertSame(original, current)
                assertEquals(1, deferred.closes); "next"
            }, {}, { invalidations++ })
            assertEquals(1, invalidations)
            assertEquals(1L, localState(runtime)["currentToken"])
        }
        assertEquals(1, original.closes); assertEquals(1, deferred.closes)
    }

    @Test fun aDeferredPhoneCancelsBothOffersAndAllowsTheNextIndependentLocalUpdate() = withRuntime { runtime ->
        val rejectedPhone = Frame(100, 12_000_000_000)
        val rejectedLocal = Settings(1, 20_000_000_000)
        var invalidations = 0
        UltraFrameCoordinator<Frame>(runtime, "phone-video").use { frames ->
            command(runtime, "deferPhone")
            assertNull(frames.composeWithSettings(rejectedPhone, rejectedLocal,
                { _, _, _, _, _ -> error("Deferred phone cannot draw") }, {}, { invalidations++ }))
            assertEquals(1, rejectedPhone.closes); assertEquals(1, rejectedLocal.closes)
            assertEquals(1, invalidations)
            assertEquals(0L, localState(runtime)["pendingToken"])
            frames.composeWithSettings(Frame(101, 12_100_000_000), Settings(2, 21_000_000_000),
                { _, _, _, offered, _ -> assertEquals(2L, offered?.token); "ok" }, {})
        }
    }

    @Test fun clearingSettingsRetiresNativeReferencesBeforePixelsAndPreservesItsWatermarks() = withRuntime { runtime ->
        val local = Settings(1, 20_000_000_000)
        UltraFrameCoordinator<Frame>(runtime, "phone-video").use { frames ->
            frames.composeWithSettings(Frame(100, 12_000_000_000), local, { _, _, _, _, _ -> "first" }, {})
            frames.clearSettings()
            assertEquals(1, local.closes)
            assertEquals(0L, localState(runtime)["currentToken"])
            val stale = Settings(1, 20_000_000_000)
            frames.composeWithSettings(Frame(101, 12_100_000_000), stale,
                { _, _, _, offered, current -> assertNull(offered); assertNull(current); "phone-only" }, {})
            assertEquals(1, stale.closes)
            frames.composeWithSettings(Frame(102, 12_200_000_000), Settings(2, 21_000_000_000),
                { _, _, _, offered, current -> assertEquals(2L, offered?.token); assertNull(current); "fresh" }, {})
        }
    }

    @Test fun aFailureDuringTheSecondCommitDiscardsOutputAndRetiresEveryLease() = withRuntime { runtime ->
        val phone = Frame(100, 12_000_000_000)
        val local = Settings(999, 20_000_000_000)
        val discarded = mutableListOf<String>()
        val frames = UltraFrameCoordinator<Frame>(runtime, "phone-video")
        assertThrows(IllegalStateException::class.java) {
            frames.composeWithSettings(phone, local, { _, _, _, _, _ -> "uncommitted-output" }, { discarded += it })
        }
        assertEquals(listOf("uncommitted-output"), discarded)
        assertEquals(1, phone.closes); assertEquals(1, local.closes)
        frames.close()
        assertEquals(1, phone.closes); assertEquals(1, local.closes)
        assertThrows(IllegalStateException::class.java) { runtime.request(6) }
    }

    @Test fun anExplicitProviderIsPausedInvalidatedAndClosedWithItsConnection() = withRuntime { runtime ->
        val first = Settings(1, 20_000_000_000)
        val next = Settings(2, 21_000_000_000)
        val source = Source(mutableListOf(first, next))
        val session = UltraComposedPanelSession<Frame>(runtime, "phone-video",
            UltraSceneInputMode.REFERENCE_CENTER_ACCESSORY, localSettings = source)
        val dormant = Frame(100, 12_000_000_000)
        assertNull(session.composeWithSettings(dormant, { _, _, _, _, _ -> error("Inactive") }, {}))
        assertEquals(0, source.calls); assertEquals(1, dormant.closes)
        session.updateHostVisibility(true, true); session.requestAccessoryScene(true)
        session.composeWithSettings(Frame(101, 12_100_000_000), { _, _, _, offered, _ ->
            assertSame(first, offered); "first"
        }, {})
        // A pause/resume coalesced between worker calls must still clear/invalidate Settings.
        session.updateHostVisibility(false, true); session.updateHostVisibility(true, true)
        session.composeWithSettings(Frame(102, 12_200_000_000), { _, _, _, offered, current ->
            assertEquals(1, first.closes); assertNull(current); assertSame(next, offered); "next"
        }, {})
        assertEquals(1, source.invalidations)
        assertEquals(listOf(12_100_000_000, 12_200_000_000), source.phoneTimes)
        session.close(); session.close()
        assertEquals(1, source.closes); assertEquals(1, next.closes)
        val late = Frame(103, 12_300_000_000)
        assertNull(session.composeWithSettings(late, { _, _, _, _, _ -> error("Closed") }, {}))
        assertEquals(1, late.closes); assertEquals(2, source.calls)
    }

    @Test fun aStalePhoneInvalidatesAnAlreadyProducedLocalFrameForRedelivery() = withRuntime { runtime ->
        val first = Settings(1, 20_000_000_000)
        val lost = Settings(2, 21_000_000_000)
        val retry = Settings(3, 21_000_000_000)
        val source = Source(mutableListOf(first, lost, retry))
        UltraComposedPanelSession<Frame>(runtime, "phone-video", localSettings = source).use { session ->
            session.updateHostVisibility(true, true)
            session.composeWithSettings(Frame(100, 12_000_000_000), { _, _, _, _, _ -> "first" }, {})
            assertNull(session.composeWithSettings(Frame(99, 11_000_000_000),
                { _, _, _, _, _ -> error("Stale phone") }, {}))
            assertEquals(1, lost.closes); assertEquals(1, source.invalidations)
            session.composeWithSettings(Frame(101, 12_100_000_000), { _, _, _, offered, current ->
                assertSame(retry, offered); assertSame(first, current); "redelivered"
            }, {})
        }
        assertEquals(1, retry.closes); assertEquals(1, source.closes)
    }

    @Test fun defaultSessionRequiresNoLocalInputDeclarationOrProvider() = withRuntime(settings = false) { runtime ->
        UltraComposedPanelSession<Frame>(runtime, "phone-video").use { session ->
            assertNotNull(session.composeWithSettings(Frame(1, 1_000_000_000), { _, _, _, offered, current ->
                assertNull(offered); assertNull(current); "phone-only"
            }, {}))
        }
    }

    @Test fun aScriptFlushClosesBothLocalLeasesAndRequestsFreshPixels() = withRuntime { runtime ->
        val current = Settings(1, 20_000_000_000)
        val offered = Settings(2, 21_000_000_000)
        var invalidations = 0
        UltraFrameCoordinator<Frame>(runtime, "phone-video").use { frames ->
            frames.composeWithSettings(Frame(100, 12_000_000_000), current, { _, _, _, _, _ -> "first" }, {})
            command(runtime, "flushSettings")
            frames.composeWithSettings(Frame(101, 12_100_000_000), offered, { _, _, _, next, previous ->
                assertNull(next); assertNull(previous); "phone-only"
            }, {}, { invalidations++ })
            assertEquals(1, current.closes); assertEquals(1, offered.closes)
            assertEquals(1, invalidations)
        }
    }

    @Test fun providerFailureRetiresTheConnectionAndTheUntransferredPhoneLease() = withRuntime { runtime ->
        var closes = 0
        val source = object : UltraLocalSettingsFrames {
            override fun nextFrame(phoneSenderNanos: Long): UltraLocalSettingsFrame? = error("Fixture source failure")
            override fun invalidate() = Unit
            override fun close() { closes++ }
        }
        val session = UltraComposedPanelSession<Frame>(runtime, "phone-video", localSettings = source)
        session.updateHostVisibility(true, true)
        val phone = Frame(100, 12_000_000_000)
        assertThrows(IllegalStateException::class.java) {
            session.composeWithSettings(phone, { _, _, _, _, _ -> error("No frame") }, {})
        }
        assertEquals(1, phone.closes); assertEquals(1, closes)
        session.close()
        assertEquals(1, closes)
    }

    @Test fun aScriptFlushWithoutAnUpdateStillInvalidatesTheRetainedSettings() = withRuntime { runtime ->
        val current = Settings(1, 20_000_000_000)
        var invalidations = 0
        UltraFrameCoordinator<Frame>(runtime, "phone-video").use { frames ->
            frames.composeWithSettings(Frame(100, 12_000_000_000), current, { _, _, _, _, _ -> "first" }, {})
            command(runtime, "flushSettings")
            frames.composeWithSettings(Frame(101, 12_100_000_000), null, { _, _, _, offered, retained ->
                assertNull(offered); assertNull(retained); "phone-only"
            }, {}, { invalidations++ })
            assertEquals(1, current.closes)
            assertEquals(1, invalidations)
            frames.composeWithSettings(Frame(102, 12_200_000_000), Settings(2, 21_000_000_000),
                { _, _, _, offered, _ -> assertEquals(2L, offered?.token); "fresh" }, {})
        }
    }
}
