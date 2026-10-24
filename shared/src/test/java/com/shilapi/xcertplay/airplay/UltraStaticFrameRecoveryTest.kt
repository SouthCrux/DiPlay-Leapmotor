package com.shilapi.xcertplay.airplay

import android.graphics.Bitmap
import com.shilapi.xcertplay.media.RetainedVideoFrameWorker
import com.shilapi.xcertplay.media.VideoCompositionFrame
import com.shilapi.xcertplay.media.VideoSurfaceOrigins
import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real frame worker + JNI frame transactions, with one decoded image and no second codec offer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UltraStaticFrameRecoveryTest {
    private fun withSession(block: (UltraComposedPanelSession<VideoCompositionFrame>) -> Unit) {
        val library = System.getenv("DIPLAY_ULTRA_JNI_LIBRARY")
        val bridge = System.getenv("DIPLAY_ULTRA_BRIDGE_SOURCE")
        assumeTrue("Explicit JNI fixture required", library != null && bridge != null)
        val root = Files.createTempDirectory("ultra-static-recovery").toFile()
        try {
            File(root, "APL").mkdir()
            File(root, "APL/json.lua").writeText("return {encode=function(v)return tostring(CP.PTS) end}")
            val entry = """
                Offscreen.configureOutput("fixture",40,30)
                Offscreen.configureSceneLifecycle()
                Offscreen.configureVideoInput("iPhoneVideo",4,4,1/30)
                local root=CP.DPLayer.create();root.frame=CP.DPRect(0,0,40,30)
                local phone=CP.DPVideoLayer.create("iPhoneVideo");phone.frame=CP.DPRect(0,0,40,30)
                root:addSublayer(phone);phone:subscribe();CP.setRootLayer("fixture",root)
                Offscreen.startProtocolForResearch()
                local task=CP.DPLuaTask.create()
                task:run(function()while true do
                    if not CP.isForegrounded then phone:flush() end
                    coroutine.yield()
                end end)
                return "initialized"
            """.trimIndent()
            val runtime = UltraNativeRuntime.open(root, File(bridge!!).readBytes(), entry.toByteArray(),
                loadLibrary = { System.load(library!!) })
            UltraComposedPanelSession<VideoCompositionFrame>(runtime, "iPhoneVideo",
                UltraSceneInputMode.REFERENCE_CENTER_ACCESSORY).use(block)
        } finally { root.deleteRecursively() }
    }

    @Test fun pausedAndFlushedSceneRecoversWithoutAnyNewDecodedFrameOrPhoneTime() = withSession { session ->
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val publications = LinkedBlockingQueue<Pair<Long, Long>>()
        val errors = AtomicInteger()
        session.requestAccessoryScene(true)
        session.updateHostVisibility(true, true)
        val worker = RetainedVideoFrameWorker({ frame ->
            val token = session.publicationToken()
            val output = session.compose(frame, { _, offered, _ ->
                assertSame(bitmap, offered.bitmap)
                offered.token to offered.senderNanos
            }, {})
            if (output != null && session.allowsPublication(token)) publications.add(output)
        }, { errors.incrementAndGet() })
        try {
            worker.offer(VideoCompositionFrame(VideoSurfaceOrigins.newToken(), 12_000_000_000L, bitmap))
            val first = checkNotNull(publications.poll(5, TimeUnit.SECONDS))
            session.updateHostVisibility(false, true)
            session.poll() // The fixture flushes its native input while the host is hidden.
            session.updateHostVisibility(true, true)
            worker.requestRefresh()
            val resumed = checkNotNull(publications.poll(5, TimeUnit.SECONDS))
            assertTrue(resumed.first > first.first)
            assertEquals(first.second, resumed.second)
            assertEquals(0, errors.get())
            assertFalse(bitmap.isRecycled)
        } finally {
            worker.close(); assertTrue(worker.awaitClosed(5_000))
            session.close()
        }
        assertTrue(bitmap.isRecycled)
    }

    @Test fun sceneBecomingAvailableRetriesTheSameDecodedPixelsAfterItsFirstOfferWasCanceled() = withSession { session ->
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val unavailable = AtomicBoolean(true)
        val attempts = LinkedBlockingQueue<String>()
        val errors = AtomicInteger()
        session.requestAccessoryScene(true)
        session.updateHostVisibility(true, true)
        val worker = RetainedVideoFrameWorker({ frame ->
            val output = session.compose(frame, { _, offered, _ ->
                assertSame(bitmap, offered.bitmap)
                if (unavailable.get()) {
                    attempts.add("unavailable")
                    throw UltraSceneUnavailableException()
                }
                assertEquals(12_000_000_000L, offered.senderNanos)
                "published"
            }, {})
            output?.let(attempts::add)
        }, { errors.incrementAndGet() })
        try {
            worker.offer(VideoCompositionFrame(VideoSurfaceOrigins.newToken(), 12_000_000_000L, bitmap))
            assertEquals("unavailable", attempts.poll(5, TimeUnit.SECONDS))
            unavailable.set(false)
            worker.requestRefresh()
            assertEquals("published", attempts.poll(5, TimeUnit.SECONDS))
            assertEquals(0, errors.get())
        } finally {
            worker.close(); assertTrue(worker.awaitClosed(5_000))
            session.close()
        }
        assertTrue(bitmap.isRecycled)
    }
}
