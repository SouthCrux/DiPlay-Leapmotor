package com.shilapi.xcertplay.airplay

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.shilapi.xcertplay.media.TimedVideoFrame
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in tests execute the actual JNI library, never a mock of the native ABI or a phone connection. */
class UltraNativeRuntimeTest {
    private fun input(name: String): File {
        val value = System.getenv(name)
        assumeTrue("Explicit local development input required: $name", value != null)
        return File(value!!).absoluteFile.also { require(it.exists()) }
    }
    private fun bridge() = input("DIPLAY_ULTRA_BRIDGE_SOURCE").readBytes()
    private fun load() { System.load(input("DIPLAY_ULTRA_JNI_LIBRARY").path) }
    private fun envelope(panel: String, bytes: ByteArray) = Opack.encode(Opack.dict(
        "displayUUID" to Opack.Text(panel), "data" to Opack.Data(bytes)))
    private fun inner(bytes: ByteArray): Map<String, Any?> =
        BoundedPlist.decode(((Opack.decode(bytes) as Opack.Dict)["data"] as Opack.Data).bytes())!!

    @Test fun nativeVmPersistsAcrossRoutedMessagesAndReleasesFailedSessions() {
        val source = bridge()
        val directory = Files.createTempDirectory("diplay-ultra-jni-test").toFile()
        try {
            val entry = """
                Offscreen.configureOutput("fixture",40,30)
                Offscreen.startProtocolForResearch()
                local count=0
                local task=CP.DPLuaTask.create()
                task:run(function()while true do
                    local readable=CP.status()
                    if readable then
                        count+=1
                        local value=assert(CP.parsePlist(CP.receiveMessage()))
                        CP.sendMessage(CP.createPlist({count=count,echo=value}))
                    end
                    coroutine.yield()
                end end)
                return "initialized"
            """.trimIndent().toByteArray()
            val runtime = UltraNativeRuntime.open(directory, source, entry, loadLibrary = ::load)
            UiSyncPanelRouter(setOf("center")) { UltraScriptPanelSession(runtime) }.use { router ->
                for (count in 1L..3L) {
                    val pts = count.toDouble() + 0.125
                    val reply = inner(router.receive(envelope("center", BplistCodec.encode(mapOf("pts" to pts)))).single())
                    assertEquals(count, reply["count"])
                    assertEquals(mapOf("pts" to pts), reply["echo"])
                }
            }
            assertThrows(IllegalStateException::class.java) { runtime.request(6) }
            // Invalid native operation terminates its VM; a fresh one remains usable.
            UltraNativeRuntime.open(directory, source, entry, loadLibrary = ::load).use { failed ->
                assertThrows(IllegalStateException::class.java) { failed.request(2, byteArrayOf(0)) }
                assertThrows(IllegalStateException::class.java) { failed.request(6) }
            }
            UltraNativeRuntime.open(directory, source, entry, loadLibrary = ::load).use {
                assertEquals(emptyList<Any>(), it.request(6))
            }
        } finally { directory.delete() }
    }

    @Test fun originalScriptsCompleteResetThroughKotlinRouterAndJni() {
        val resources = input("DIPLAY_ULTRA_TEST_RESOURCES")
        val source = bridge()
        val entry = input("DIPLAY_ULTRA_ENTRY_SOURCE").readBytes()
        val runtime = UltraNativeRuntime.open(resources, source, entry, researchOverride = true, loadLibrary = ::load)
        UiSyncPanelRouter(setOf("center")) { UltraScriptPanelSession(runtime) }.use { router ->
            val reset = inner(router.start("center").single())
            assertEquals(0L, reset["type"])
            val received = mutableListOf<Map<String, Any?>>()
            received += router.receive(envelope("center", BplistCodec.encode(mapOf(
                "type" to 1L, "ssn" to reset.getValue("ssn"), "psn" to 1L,
                "asn" to reset.getValue("psn"), "version" to 2L, "id" to "fixture-peer",
            )))).map(::inner)
            repeat(8) { received += router.poll().map(::inner) }
            assertTrue(received.any { it["type"] == 1L })
            // Simulated lifecycle is explicit and kept separate from real vehicle inputs.
            runtime.request(5, byteArrayOf(1)); runtime.request(4, byteArrayOf(4))
            val commands = mutableListOf<Map<String, Any?>>()
            repeat(10) {
                commands += router.poll().map(::inner).filter { it["type"] == 2L }
                    .mapNotNull { (it["data"] as? ByteArray)?.let(BoundedPlist::decode) }
            }
            assertTrue(commands.any { it["type"] == 5L })
            val scene = String(runtime.request(3) as ByteArray, Charsets.UTF_8)
            assertTrue(Regex("\"fullUltraUI\"\\s*:\\s*false").containsMatchIn(scene))
        }
    }

    @Test fun nativeHandlesAreBoundedRetiredAndCannotAliasReplacementSessions() {
        load()
        val directory = Files.createTempDirectory("diplay-ultra-jni-handles").toFile()
        val handles = mutableListOf<Long>()
        val root = directory.path.toByteArray()
        fun create() = UltraNativeBindings.create(root, byteArrayOf(), "return true".toByteArray(), false)
        try {
            repeat(3) { handles += create() }
            handles += UltraNativeBindings.createSessionOwned(root, byteArrayOf(), "return true".toByteArray(), false)
            assertEquals(4, handles.toSet().size)
            assertThrows(IllegalStateException::class.java) { create() }
            assertThrows(IllegalStateException::class.java) {
                UltraNativeBindings.createSessionOwned(root, byteArrayOf(), "return true".toByteArray(), false)
            }
            val retired = handles.removeAt(0)
            UltraNativeBindings.destroy(retired)
            UltraNativeBindings.destroy(retired)
            handles += create()
            assertFalse(retired in handles)
            assertThrows(IllegalStateException::class.java) { UltraNativeBindings.step(retired, 0, byteArrayOf()) }
        } finally {
            handles.forEach(UltraNativeBindings::destroy)
            directory.delete()
        }
    }

    private class Frame(override val token: Long, override val senderNanos: Long) : TimedVideoFrame {
        var closes = 0
        override fun close() { closes++ }
    }

    private fun withFrameRuntime(
        encode: String = "return tostring(v.scene.children[1].video.token or 0)",
        declareScene: Boolean = false,
        block: (UltraNativeRuntime) -> Unit,
    ) {
        val directory = Files.createTempDirectory("diplay-ultra-frame-test").toFile()
        try {
            File(directory, "APL").mkdir()
            File(directory, "APL/json.lua").writeText("return {encode=function(v)$encode end}")
            val entry = """
                Offscreen.configureOutput("fixture",40,30)
                ${if (declareScene) "Offscreen.configureSceneLifecycle()" else ""}
                Offscreen.configureVideoInput("phone-video",4,4,1/30)
                local root=CP.DPLayer.create();root.frame=CP.DPRect(0,0,40,30)
                local video=CP.DPVideoLayer.create("phone-video");video.frame=CP.DPRect(0,0,40,30)
                root:addSublayer(video);CP.setRootLayer("fixture",root);video:subscribe()
                Offscreen.startProtocolForResearch()
                local transition=nil
                local task=CP.DPLuaTask.create()
                task:run(function()while true do
                    if CP.status() then
                        local command=assert(CP.parsePlist(CP.receiveMessage()))
                        if command.action=="defer" then video:deferNextPTS()
                        elseif command.action=="flush" then video:flush()
                        elseif command.action=="transition" then
                            transition=command
                            CP.sendMessage(CP.createPlist({received=true}))
                        else error("fixture action") end
                    end
                    if transition and CP.PTS>=transition.pts then
                        video.opacity=transition.opacity;transition=nil
                    end
                    coroutine.yield()
                end end)
                return "initialized"
            """.trimIndent().toByteArray()
            UltraNativeRuntime.open(directory, bridge(), entry, loadLibrary = ::load).use(block)
        } finally { directory.deleteRecursively() }
    }

    @Test fun frameTransactionBorrowsCurrentUntilSuccessfulReplacementAndRejectsStaleInput() = withFrameRuntime { runtime ->
        val first = Frame(100, 12_125_000_000)
        val next = Frame(101, 12_158_000_000)
        UltraFrameCoordinator<Frame>(runtime, "phone-video").use { frames ->
            val firstResult = frames.compose(first, { scene, offered, previous ->
                assertNull(previous); assertSame(first, offered); assertEquals("100", String(scene))
                "composed-first"
            }, { error("No discarded output") })
            assertEquals("composed-first", firstResult); assertEquals(0, first.closes)
            frames.compose(next, { scene, offered, previous ->
                assertSame(first, previous); assertSame(next, offered); assertEquals(0, first.closes)
                assertEquals("101", String(scene)); "composed-next"
            }, { error("No discarded output") })
            assertEquals(1, first.closes); assertEquals(0, next.closes)
            val stale = Frame(99, 12_000_000_000)
            assertNull(frames.compose(stale, { _, _, _ -> error("Stale frame rendered") }, {}))
            assertEquals(1, stale.closes)
        }
        assertEquals(1, next.closes)
    }

    @Test fun deferredAndFlushedFramesCannotReplaceCurrentAndDrawingFailureRetiresTheVm() = withFrameRuntime { runtime ->
        val current = Frame(100, 12_125_000_000)
        UltraFrameCoordinator<Frame>(runtime, "phone-video").use { frames ->
            frames.compose(current, { _, _, _ -> "ok" }, {})
            runtime.request(1, BplistCodec.encode(mapOf("action" to "defer")))
            val deferred = Frame(101, 12_158_000_000)
            assertNull(frames.compose(deferred, { _, _, _ -> error("Deferred frame rendered") }, {}))
            assertEquals(1, deferred.closes); assertEquals(0, current.closes)
            runtime.request(1, BplistCodec.encode(mapOf("action" to "flush")))
            val flushed = Frame(102, 12_191_000_000)
            assertNull(frames.compose(flushed, { _, _, _ -> error("Flushed frame rendered") }, {}))
            assertEquals(1, flushed.closes); assertEquals(1, current.closes)
            val failure = Frame(103, 12_224_000_000)
            assertThrows(IllegalArgumentException::class.java) {
                frames.compose(failure, { _, _, _ -> throw IllegalArgumentException("fixture drawing failure") }, {})
            }
            assertEquals(1, failure.closes)
            assertThrows(IllegalStateException::class.java) { runtime.request(6) }
        }
    }

    @Test fun drawingErrorsAlsoRetireLeasesAndNativeState() = withFrameRuntime { runtime ->
        val frame = Frame(100, 12_125_000_000)
        UltraFrameCoordinator<Frame>(runtime, "phone-video").use { frames ->
            assertThrows(AssertionError::class.java) {
                frames.compose(frame, { _, _, _ -> throw AssertionError("fixture renderer error") }, {})
            }
            assertEquals(1, frame.closes)
            assertThrows(IllegalStateException::class.java) { runtime.request(6) }
        }
    }

    @Test fun onePanelOwnerJoinsRoutedMessageSourceTimeAndComposedState() = withFrameRuntime(
        "return tostring(v.scene.children[1].video.token or 0)..':'..tostring(v.scene.children[1].opacity or 1)",
    ) { runtime ->
        val session = UltraComposedPanelSession<Frame>(runtime, "phone-video")
        val first = Frame(100, 12_125_000_000)
        val next = Frame(101, 12_225_000_000)
        UiSyncPanelRouter(setOf("center")) { session }.use { router ->
            val receipt = router.receive(envelope("center", BplistCodec.encode(mapOf(
                "action" to "transition", "pts" to 12.2, "opacity" to 0.25,
            )))).single()
            assertEquals(true, inner(receipt)["received"])
            repeat(3) { assertTrue(router.poll().isEmpty()) }
            // Receipt and protocol polling alone cannot advance the video clock/apply this change.
            assertEquals("100:1", session.compose(first, { bytes, _, _ -> String(bytes) }, {}))
            assertEquals("101:0.25", session.compose(next, { bytes, _, previous ->
                assertSame(first, previous); assertEquals(0, first.closes)
                String(bytes)
            }, {}))
            assertEquals(1, first.closes)
        }
        assertEquals(1, next.closes)
        val late = Frame(102, 12_258_000_000)
        assertNull(session.compose(late, { _, _, _ -> error("Closed session rendered") }, {}))
        assertEquals(1, late.closes)
        assertThrows(IllegalStateException::class.java) { runtime.request(6) }
    }

    @Test fun protocolFailureRetiresRetainedFrameAndReplacementStartsEmpty() {
        val oldFrame = Frame(100, 12_125_000_000)
        withFrameRuntime { runtime ->
            val session = UltraComposedPanelSession<Frame>(runtime, "phone-video")
            UiSyncPanelRouter(setOf("center")) { session }.use { router ->
                router.start("center")
                session.compose(oldFrame, { _, _, previous -> assertNull(previous); "old" }, {})
                assertThrows(IllegalStateException::class.java) {
                    router.receive(envelope("center", BplistCodec.encode(mapOf("action" to "unsupported"))))
                }
                assertEquals(1, oldFrame.closes)
                assertThrows(IllegalStateException::class.java) { session.poll() }
            }
        }
        withFrameRuntime { runtime ->
            UltraComposedPanelSession<Frame>(runtime, "phone-video").use { replacement ->
                // A new connection may restart its source clock; no old lease or watermark survives.
                val fresh = Frame(1, 1_000_000_000)
                assertEquals("1", replacement.compose(fresh, { bytes, _, previous ->
                    assertNull(previous); String(bytes)
                }, {}))
            }
        }
        assertEquals(1, oldFrame.closes)
    }

    @Test fun hostVisibilityAppliesAtWorkerBoundariesWithoutInventingSceneOwnership() = withFrameRuntime(
        "return tostring(CP.isForegrounded)..':'..tostring(v.scene.children[1].video.token or 0)",
    ) { runtime ->
        val session = UltraComposedPanelSession<Frame>(runtime, "phone-video")
        session.use {
            assertEquals("false:100", session.compose(Frame(100, 12_000_000_000), { bytes, _, _ -> String(bytes) }, {}))
            session.updateHostVisibility(resumed = true, surfaceReady = true)
            // A host callback does not run JNI; it merely records pending state.
            assertEquals("false:100", String(runtime.request(3) as ByteArray))
            session.poll()
            assertEquals("true:100", String(runtime.request(3) as ByteArray))
            session.updateHostVisibility(resumed = true, surfaceReady = false)
            assertEquals("false:101", session.compose(Frame(101, 12_030_000_000), { bytes, _, _ -> String(bytes) }, {}))
            session.updateHostVisibility(resumed = true, surfaceReady = true)
            session.updateHostVisibility(resumed = false, surfaceReady = true)
            session.receive(BplistCodec.encode(mapOf("action" to "transition", "pts" to 99.0, "opacity" to 0.5)))
            assertEquals("false:101", String(runtime.request(3) as ByteArray))
        }
        session.updateHostVisibility(resumed = true, surfaceReady = true)
        assertThrows(IllegalStateException::class.java) { session.poll() }
    }

    @Test fun hostCallbackDoesNotWaitForAnActiveComposition() = withFrameRuntime { runtime ->
        val session = UltraComposedPanelSession<Frame>(runtime, "phone-video")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val draw = pool.submit {
                session.compose(Frame(100, 12_000_000_000), { _, _, _ ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    "output"
                }, {})
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val callback = pool.submit { session.updateHostVisibility(resumed = true, surfaceReady = true) }
            callback.get(1, TimeUnit.SECONDS)
            release.countDown()
            draw.get(5, TimeUnit.SECONDS)
            session.poll()
        } finally {
            release.countDown()
            pool.shutdownNow()
            check(pool.awaitTermination(5, TimeUnit.SECONDS))
            session.close()
        }
    }

    @Test fun lifecycleFailureBeforeFrameHandoffClosesTheOfferedLease() = withFrameRuntime { runtime ->
        val session = UltraComposedPanelSession<Frame>(runtime, "phone-video")
        val frame = Frame(100, 12_000_000_000)
        runtime.close()
        assertThrows(IllegalStateException::class.java) {
            session.compose(frame, { _, _, _ -> error("Retired VM must not draw") }, {})
        }
        assertEquals(1, frame.closes)
        session.close()
        session.updateHostVisibility(resumed = true, surfaceReady = true)
        assertEquals(1, frame.closes)
    }

    @Test fun centerPolicyDropsInactiveFramesAndResumesOnlyAfterAnExplicitRequest() = withFrameRuntime(declareScene = true) { runtime ->
        val session = UltraComposedPanelSession<Frame>(runtime, "phone-video", UltraSceneInputMode.REFERENCE_CENTER_ACCESSORY)
        val dormant = Frame(100, 12_000_000_000)
        val first = Frame(101, 12_030_000_000)
        val paused = Frame(102, 12_060_000_000)
        val withdrawn = Frame(103, 12_090_000_000)
        val resumed = Frame(104, 12_120_000_000)
        session.use {
            session.updateHostVisibility(resumed = true, surfaceReady = true)
            assertFalse(session.allowsPublication())
            assertNull(session.compose(dormant, { _, _, _ -> error("No scene requested") }, {}))
            assertEquals(1, dormant.closes)
            session.requestAccessoryScene(true)
            assertTrue(session.allowsPublication())
            assertEquals("101", session.compose(first, { bytes, _, _ -> String(bytes) }, {}))
            session.updateHostVisibility(resumed = true, surfaceReady = false)
            assertFalse(session.allowsPublication())
            assertNull(session.compose(paused, { _, _, _ -> error("Surface unavailable") }, {}))
            assertEquals(1, paused.closes)
            session.requestAccessoryScene(false)
            session.updateHostVisibility(resumed = true, surfaceReady = true)
            assertNull(session.compose(withdrawn, { _, _, _ -> error("Request withdrawn") }, {}))
            assertEquals(1, withdrawn.closes)
            session.requestAccessoryScene(true)
            assertEquals("104", session.compose(resumed, { bytes, _, previous ->
                assertSame(first, previous)
                String(bytes)
            }, {}))
            assertEquals(1, first.closes)
        }
        assertFalse(session.allowsPublication())
        assertEquals(1, resumed.closes)
    }

    @Test fun invalidCenterPolicyContractRetiresTheVmBeforeOpeningItsDecoder() = withFrameRuntime(declareScene = true) { runtime ->
        assertThrows(IllegalArgumentException::class.java) {
            UltraVideoPipeline("Cluster_Display", "phone-video", 64, 48, runtime,
                { _, _, _ -> error("Invalid contract must not render") }, {},
                sceneInputMode = UltraSceneInputMode.REFERENCE_CENTER_ACCESSORY)
        }
        assertThrows(IllegalStateException::class.java) { runtime.request(6) }
    }

    @Test fun disconnectWaitsForActiveCompositionBeforeRetiringItsFrame() = withFrameRuntime { runtime ->
        val session = UltraComposedPanelSession<Frame>(runtime, "phone-video")
        val enteredDraw = CountDownLatch(1)
        val releaseDraw = CountDownLatch(1)
        val enteredClose = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val frame = Frame(100, 12_125_000_000)
        try {
            val composed = pool.submit<String?> {
                session.compose(frame, { _, _, _ ->
                    enteredDraw.countDown()
                    check(releaseDraw.await(5, TimeUnit.SECONDS))
                    assertEquals(0, frame.closes)
                    "independent-output"
                }, {})
            }
            assertTrue(enteredDraw.await(5, TimeUnit.SECONDS))
            val disconnect = pool.submit {
                enteredClose.countDown()
                session.close()
                closed.countDown()
            }
            assertTrue(enteredClose.await(5, TimeUnit.SECONDS))
            assertFalse(closed.await(100, TimeUnit.MILLISECONDS))
            releaseDraw.countDown()
            assertEquals("independent-output", composed.get(5, TimeUnit.SECONDS))
            disconnect.get(5, TimeUnit.SECONDS)
            assertEquals(1, frame.closes)
            assertThrows(IllegalStateException::class.java) { runtime.request(6) }
        } finally {
            releaseDraw.countDown()
            pool.shutdownNow()
            check(pool.awaitTermination(5, TimeUnit.SECONDS))
            session.close()
        }
    }
}
