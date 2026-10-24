package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCrypto
import android.media.MediaFormat
import android.view.Surface
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodec
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Low-latency decoding releases decoded frames from a thread of its own, which stops with the decoder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VideoOutputThreadTest {
    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1E)
    private val pps = byteArrayOf(0x68, 0xCE.toByte())
    private val avcC = byteArrayOf(1, 0x42, 0xC0.toByte(), 0x1E, 0xFF.toByte(), 0xE1.toByte(), 0, sps.size.toByte()) +
        sps + byteArrayOf(1, 0, pps.size.toByte()) + pps
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)
    private val uncaught = ConcurrentLinkedQueue<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private object PassThroughCodec : ShadowMediaCodec.CodecConfig.Codec {
        override fun process(input: ByteBuffer, output: ByteBuffer) = Unit
        override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) = Unit
    }

    @Before fun setUp() {
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024, PassThroughCodec))
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught += error }
    }

    @After fun tearDown() {
        ShadowMediaCodec.clearCodecs()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test fun theOutputThreadRendersFramesAndStopsWithTheDecoder() {
        val reports = CopyOnWriteArrayList<String>()
        val rendered = CountDownLatch(1)
        val sink = AndroidMediaSink(surface = null, vendorLowLatencyDecoder = true)
        sink.setVideoDiagnosticHandler(MAIN) { message ->
            reports += message
            if (message == "first frame rendered") rendered.countDown()
        }
        val surface = Surface(SurfaceTexture(0))
        sink.setSurface(MAIN, surface)
        sink.onVideoConfig(MAIN, avcC)
        sink.onVideoFrame(MAIN, idr)
        assertTrue("a decoded frame was rendered", rendered.await(5, TimeUnit.SECONDS))
        assertTrue(reports.toString(), reports.any { it.startsWith("decoder=") && it.endsWith(" outputThread=true") })

        val decoder = mainDecoder(sink)
        val output = checkNotNull(decoder.field<Thread?>("outputThread")) { "no output thread" }
        assertTrue(output.isAlive)

        // Losing the surface releases the codec the output thread is waiting on.
        assertTrue(sink.beginSurfaceDetach(surface, parkMain = false).await())
        assertNull(decoder.field<MediaCodec?>("decoder"))

        sink.close()
        assertTrue(sink.awaitVideoReleased(5_000))
        output.join(5_000)
        assertFalse("the output thread exits with the worker", output.isAlive)
        assertTrue(uncaught.toString(), uncaught.isEmpty())
        assertFalse(reports.toString(), reports.any { it.startsWith("decoder failed") })
    }

    @Test fun withoutLowLatencyDecodingTheWorkerDrainsItsOwnOutput() {
        val sink = AndroidMediaSink(surface = null)
        try {
            sink.setSurface(MAIN, Surface(SurfaceTexture(0)))
            sink.onVideoConfig(MAIN, avcC)
            sink.onVideoFrame(MAIN, idr)
            assertTrue(sink.beginSurfaceDetach(Surface(SurfaceTexture(0)), parkMain = false).await())
            assertNull(mainDecoder(sink).field<Thread?>("outputThread"))
        } finally {
            sink.close()
            sink.awaitVideoReleased(5_000)
        }
    }

    @Test fun phoneTimestampSurvivesPacedAndUnpacedDecodeOnEitherOutputPath() {
        for ((dedicated, delay) in listOf(false to 0, true to 0, true to 50)) {
            val frames = CopyOnWriteArrayList<VideoFrameRelease>()
            val released = CountDownLatch(1)
            val sink = AndroidMediaSink(surface = null, vendorLowLatencyDecoder = dedicated, videoPacingDelayMillis = delay,
                onVideoFrameRelease = { type, frame ->
                    assertEquals(MAIN, type); frames += frame; released.countDown()
                })
            val surface = Surface(SurfaceTexture(0))
            try {
                sink.setSurface(MAIN, surface); sink.onVideoConfig(MAIN, avcC)
                sink.onVideoFrame(MAIN, idr, 1234567890123456L, System.nanoTime())
                assertTrue("release callback dedicated=$dedicated", released.await(5, TimeUnit.SECONDS))
                assertEquals(1234567890123456L, frames.single().senderNanos)
                assertTrue(frames.single().scheduledLocalNanos > 0)
            } finally {
                sink.close(); assertTrue(sink.awaitVideoReleased(5_000)); surface.release()
            }
        }
    }

    @Test fun timingObserverFailureDoesNotInterruptVideoDecode() {
        val released = CountDownLatch(2)
        val failures = CountDownLatch(2)
        val sink = AndroidMediaSink(surface = null, vendorLowLatencyDecoder = true,
            onVideoFrameRelease = { _, _ -> released.countDown(); error("fixture observer failed") })
        val surface = Surface(SurfaceTexture(0))
        sink.setVideoDiagnosticHandler(MAIN) { if (it == "video release observer failed") failures.countDown() }
        try {
            sink.setSurface(MAIN, surface); sink.onVideoConfig(MAIN, avcC)
            sink.onVideoFrame(MAIN, idr, 1000000000L, System.nanoTime())
            sink.onVideoFrame(MAIN, idr, 1016666667L, System.nanoTime())
            assertTrue(released.await(5, TimeUnit.SECONDS))
            assertTrue(failures.await(5, TimeUnit.SECONDS))
            assertTrue(mainDecoder(sink).field<Thread>("thread").isAlive)
        } finally {
            sink.close(); assertTrue(sink.awaitVideoReleased(5_000)); surface.release()
        }
    }

    @Test fun compositionUsesAUniqueBufferTokenOnlyForItsExactDestination() {
        val surface = Surface(SurfaceTexture(0))
        val origins = VideoSurfaceOrigins()
        val releases = CopyOnWriteArrayList<VideoFrameRelease>()
        val ready = CountDownLatch(1)
        val target = object : VideoCompositionTarget {
            override val surface = surface
            override fun queueFrame(senderNanos: Long) = origins.queue(senderNanos)
            override fun discardFrame(token: Long) = origins.discard(token)
        }
        val sink = AndroidMediaSink(videoCompositionTargets = mapOf(MAIN to target),
            onVideoFrameRelease = { _, frame -> releases += frame; ready.countDown() })
        try {
            sink.setSurface(MAIN, surface); sink.onVideoConfig(MAIN, avcC)
            sink.onVideoFrame(MAIN, idr, 3_000_000_000L, System.nanoTime())
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            val token = releases.single().surfaceTimestampNanos!!
            assertEquals(3_000_000_000L, origins.latched(token))
            assertNull(origins.latched(token))
        } finally {
            sink.close(); assertTrue(sink.awaitVideoReleased(5_000)); surface.release(); origins.close()
        }
    }

    private fun mainDecoder(sink: AndroidMediaSink): Any =
        checkNotNull(sink.field<Map<Int, Any>>("videoDecoders")[MAIN]) { "no main decoder" }

    @Suppress("UNCHECKED_CAST")
    private fun <T> Any.field(name: String): T {
        var type: Class<*>? = javaClass
        while (type != null) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("no field $name on $javaClass")
    }

    private companion object {
        const val MAIN = 110
    }
}
