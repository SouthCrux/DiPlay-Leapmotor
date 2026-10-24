package com.shilapi.xcertplay.media

import android.content.Context
import android.content.ContextWrapper
import android.graphics.SurfaceTexture
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
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowMediaCodec
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Observe the MediaFormat actually passed to a codec, including replacement and mirror workers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AndroidMediaSinkStreamDimensionsTest {
    private data class Configured(val surface: Surface?, val dimensions: Pair<Int, Int>)
    private val configured = LinkedBlockingQueue<Configured>()
    private val sinks = ArrayList<AndroidMediaSink>()
    private val surfaces = ArrayList<Pair<SurfaceTexture, Surface>>()
    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1E)
    private val pps = byteArrayOf(0x68, 0xCE.toByte())
    private val avcC = byteArrayOf(1, 0x42, 0xC0.toByte(), 0x1E, 0xFF.toByte(), 0xE1.toByte(), 0, sps.size.toByte()) +
        sps + byteArrayOf(1, 0, pps.size.toByte()) + pps
    private val predicted = byteArrayOf(0, 0, 0, 1, 0x41, 1)

    @Before fun setUp() {
        ShadowLog.clear()
        val recorder = object : ShadowMediaCodec.CodecConfig.Codec {
            override fun process(input: ByteBuffer, output: ByteBuffer) = Unit
            override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
                val value = checkNotNull(format)
                configured += Configured(surface,
                    value.getInteger(MediaFormat.KEY_WIDTH) to value.getInteger(MediaFormat.KEY_HEIGHT))
            }
        }
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_AVC,
            ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024, recorder))
    }

    @After fun tearDown() {
        sinks.forEach { it.close() }
        sinks.forEach { assertTrue("decoder workers released", it.awaitVideoReleased(5_000)) }
        surfaces.forEach { (texture, surface) ->
            surface.release()
            texture.release()
        }
        ShadowMediaCodec.clearCodecs()
    }

    @Test fun clusterPrimaryAndMirrorConfigureTheNegotiatedSquareInsteadOfTheMainCanvas() {
        val sink = sink(mapOf(111 to (720 to 720)))
        val main = surface()
        configure(sink, 110, main)
        assertConfigured(main, 1920 to 720)
        val cluster = surface()
        configure(sink, 111, cluster)
        assertConfigured(cluster, 720 to 720)
        val mirror = surface()
        sink.setMirrorSurface(111, "cluster-copy", mirror)
        assertConfigured(mirror, 720 to 720)
        val mainMirror = surface()
        sink.setMirrorSurface(110, "main-copy", mainMirror)
        assertConfigured(mainMirror, 1920 to 720)
    }

    @Test fun replacingAStreamAndItsMirrorRetainsAnImmutableCopyOfItsDimensions() {
        val requested = mutableMapOf(111 to (720 to 720))
        val sink = sink(requested)
        val cluster = surface()
        configure(sink, 111, cluster)
        assertConfigured(cluster, 720 to 720)
        val mirror = surface()
        sink.setMirrorSurface(111, "cluster-copy", mirror)
        assertConfigured(mirror, 720 to 720)
        requested[111] = 320 to 240
        requested[110] = 320 to 240

        sink.onScreenStreamActive(111, false)
        sink.onVideoConfig(111, avcC)
        // A retained mirror is recreated with the stream's replacement decoder. Their order is free.
        val replacements = listOf(nextConfiguration(), nextConfiguration())
        assertEquals(setOf(cluster, mirror), replacements.map { it.surface }.toSet())
        assertTrue(replacements.all { it.dimensions == (720 to 720) })
        val main = surface()
        configure(sink, 110, main)
        assertConfigured(main, 1920 to 720)
    }

    @Test fun absentOverridesPreserveMainAndOtherStreamDefaults() {
        val sink = sink(emptyMap())
        for (type in listOf(110, 111, 112)) {
            val output = surface()
            configure(sink, type, output)
            assertConfigured(output, 1920 to 720)
        }
    }

    @Test fun invalidOverridesAreRejectedBeforeAnyWorkerIsCreated() {
        val before = workers()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = error("Invalid geometry acquired a context owner")
        }
        for (dimensions in listOf(0 to 720, 720 to 0, -1 to 720, 8193 to 1, 8192 to 8192)) {
            assertThrows(IllegalArgumentException::class.java) {
                AndroidMediaSink(context = context, videoDimensionsByStream = mapOf(111 to dimensions))
            }
        }
        assertEquals(before, workers())
        assertTrue(configured.isEmpty())
    }

    @Test fun firstSubmissionIdentifiesStreamGeometryAndMirrorWithoutExportingItsKey() {
        val sink = sink(mapOf(111 to (720 to 720)))
        val primary = surface()
        configure(sink, 111, primary)
        assertConfigured(primary, 720 to 720)
        val mirror = surface()
        sink.setMirrorSurface(111, "untrusted-mirror-label", mirror)
        assertConfigured(mirror, 720 to 720)
        sink.onVideoFrame(111, predicted)
        sink.onVideoFrame(111, predicted)
        val diagnostics = ShadowLog.getLogs().map { it.msg }
            .filter { it.startsWith("video decoder first submission ") }
        assertEquals(2, diagnostics.size)
        assertTrue(diagnostics.contains("video decoder first submission type=111 width=720 height=720 composition=false mirror=false"))
        assertTrue(diagnostics.contains("video decoder first submission type=111 width=720 height=720 composition=false mirror=true"))
        assertTrue(diagnostics.none { "untrusted-mirror-label" in it })
    }

    private fun sink(dimensions: Map<Int, Pair<Int, Int>>) = AndroidMediaSink(
        videoWidth = 1920, videoHeight = 720, videoDimensionsByStream = dimensions,
    ).also(sinks::add)

    private fun surface(): Surface {
        val texture = SurfaceTexture(0)
        return Surface(texture).also { surfaces += texture to it }
    }

    private fun configure(sink: AndroidMediaSink, type: Int, surface: Surface) {
        sink.setSurface(type, surface)
        sink.onVideoConfig(type, avcC)
    }

    private fun nextConfiguration(): Configured = checkNotNull(configured.poll(5, TimeUnit.SECONDS)) {
        "No MediaCodec configuration arrived"
    }

    private fun assertConfigured(surface: Surface, dimensions: Pair<Int, Int>) {
        val value = nextConfiguration()
        assertSame(surface, value.surface)
        assertEquals(dimensions, value.dimensions)
    }

    private fun workers(): Set<Long> = Thread.getAllStackTraces().keys.filter {
        it.isAlive && it.name in setOf("carplay-video", "carplay-video-output", "carplay-video-recovery")
    }.map { it.id }.toSet()
}
