// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Looper
import com.shilapi.xcertplay.airplay.MediaSink
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Debug-only real codec/SurfaceTexture test. Uses four generated color quadrants, no phone/video file. */
object VideoCompositionSmoke {
    private const val WIDTH = 64
    private const val HEIGHT = 48
    private const val STREAM = 110
    private data class Packet(val bytes: ByteArray, val ptsUs: Long)
    private data class Clip(val config: ByteArray, val packets: List<Packet>)

    @JvmStatic fun main(args: Array<String>) {
        require(args.size <= 1) { "Optional output PNG path" }
        val saved = java.util.concurrent.atomic.AtomicBoolean()
        runCapture { frame ->
            try {
                if (args.isNotEmpty() && saved.compareAndSet(false, true)) File(args[0]).outputStream().use {
                    check(frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { frame.close() }
        }
        println("{\"realAvcEncodeDecode\":true,\"surfaceTextureLatched\":true,\"originMatched\":true," +
            "\"rgbaAndOrientationVerified\":true,\"width\":$WIDTH,\"height\":$HEIGHT," +
            "\"fixtureVideo\":true,\"phoneConnected\":false,\"fullUltraUI\":false}")
    }

    /** Transfers each validated latched frame to consume; the consumer must retain or close it. */
    fun runCapture(consume: (VideoCompositionFrame) -> Unit) {
        if (Looper.getMainLooper() == null) {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
        }
        val clip = encode()
        val expectedOrigins = clip.packets.map { 11_000_000_000L + it.ptsUs * 1000 }.toSet()
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val observed = AtomicReference<Long?>()
        val output = SurfaceTextureVideoOutput(WIDTH, HEIGHT, onFrame = { frame ->
            try {
                check(frame.senderNanos in expectedOrigins) { "Wrong frame origin" }
                val image = frame.bitmap
                fun rgb(x: Int, y: Int) = image.getPixel(x, y)
                val red = rgb(WIDTH / 4, HEIGHT / 4)
                val green = rgb(3 * WIDTH / 4, HEIGHT / 4)
                val blue = rgb(WIDTH / 4, 3 * HEIGHT / 4)
                val white = rgb(3 * WIDTH / 4, 3 * HEIGHT / 4)
                check(Color.red(red) > 180 && Color.green(red) < 100 && Color.blue(red) < 100) { "Red/orientation mismatch" }
                check(Color.green(green) > 180 && Color.red(green) < 100 && Color.blue(green) < 100) { "Green/orientation mismatch" }
                check(Color.blue(blue) > 180 && Color.red(blue) < 100 && Color.green(blue) < 100) { "Blue/orientation mismatch" }
                check(Color.red(white) > 180 && Color.green(white) > 180 && Color.blue(white) > 180) { "White mismatch" }
                observed.compareAndSet(null, frame.senderNanos)
                consume(frame)
            } catch (error: Throwable) { frame.close(); failure.compareAndSet(null, error) }
            finally { done.countDown() }
        }, onError = {
            failure.compareAndSet(null, IllegalStateException("Video output failed")); done.countDown()
        })
        val sink = AndroidMediaSink(surface = output.surface, videoWidth = WIDTH, videoHeight = HEIGHT,
            videoCompositionTargets = mapOf(STREAM to output))
        try {
            sink.onVideoConfig(STREAM, clip.config)
            for (packet in clip.packets) {
                sink.onVideoFrame(STREAM, packet.bytes, 11_000_000_000L + packet.ptsUs * 1000, System.nanoTime())
            }
            check(done.await(15, TimeUnit.SECONDS)) { "No latched decoded frame" }
            failure.get()?.let { throw it }
            check(observed.get() != null)
        } finally {
            sink.close(); check(sink.awaitVideoReleased(5_000)) { "Decoder did not retire" }
            output.close(); check(output.awaitClosed(5_000)) { "Video output did not retire" }
        }
    }

    /** Feed the real generated AVC fixture through an independently owned pipeline's decoder. */
    fun feedGeneratedVideo(sink: MediaSink, width: Int = WIDTH, height: Int = HEIGHT,
        originNanos: Long = 11_000_000_000L) {
        require(width in 2..1920 && height in 2..1080 && width % 2 == 0 && height % 2 == 0)
        require(originNanos > 0 && originNanos <= Long.MAX_VALUE - 1_000_000_000L)
        val clip = encode(width, height)
        sink.onVideoConfig(STREAM, clip.config)
        for (packet in clip.packets) {
            sink.onVideoFrame(STREAM, packet.bytes, originNanos + packet.ptsUs * 1000, System.nanoTime())
        }
    }

    private fun encode(width: Int = WIDTH, height: Int = HEIGHT): Clip {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            val packets = mutableListOf<Packet>()
            var config: ByteArray? = null
            var submitted = 0
            var ended = false
            val deadline = System.nanoTime() + 30_000_000_000L
            val colors = arrayOf(intArrayOf(82, 90, 240), intArrayOf(145, 54, 34),
                intArrayOf(41, 240, 110), intArrayOf(235, 128, 128))
            val info = MediaCodec.BufferInfo()
            while (!ended && System.nanoTime() < deadline) {
                if (submitted <= 3) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        if (submitted == 3) codec.queueInputBuffer(index, 0, 0, 200_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        else {
                            val image = checkNotNull(codec.getInputImage(index)) { "No flexible YUV input" }
                            image.use {
                                for ((planeIndex, plane) in it.planes.withIndex()) {
                                    val scale = if (planeIndex == 0) 1 else 2
                                    val buffer = plane.buffer
                                    for (y in 0 until height / scale) for (x in 0 until width / scale) {
                                        val quadrant = (if (y * scale >= height / 2) 2 else 0) +
                                            (if (x * scale >= width / 2) 1 else 0)
                                        buffer.put(y * plane.rowStride + x * plane.pixelStride, colors[quadrant][planeIndex].toByte())
                                    }
                                }
                            }
                            codec.queueInputBuffer(index, 0, width * height * 3 / 2,
                                100_000L + submitted * 33_333, 0)
                        }
                        submitted++
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        val sps = nal(checkNotNull(output.getByteBuffer("csd-0")), 7)
                        val pps = nal(checkNotNull(output.getByteBuffer("csd-1")), 8)
                        check(sps.size >= 4 && sps.size <= 65535 && pps.size in 1..65535)
                        config = ByteArrayOutputStream().use { bytes ->
                            bytes.write(byteArrayOf(1, sps[1], sps[2], sps[3], 0xff.toByte(), 0xe1.toByte(),
                                (sps.size ushr 8).toByte(), sps.size.toByte()))
                            bytes.write(sps); bytes.write(1); bytes.write(pps.size ushr 8); bytes.write(pps.size); bytes.write(pps)
                            bytes.toByteArray()
                        }
                    }
                    else -> if (index >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buffer = checkNotNull(codec.getOutputBuffer(index)).duplicate()
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size); buffer.get(bytes)
                            packets += Packet(bytes, info.presentationTimeUs)
                        }
                        ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
            check(ended && packets.isNotEmpty() && config != null) { "AVC fixture encoding incomplete" }
            return Clip(config, packets)
        } finally { runCatching { codec.stop() }; codec.release() }
    }

    private fun nal(buffer: ByteBuffer, type: Int): ByteArray {
        val bytes = ByteArray(buffer.remaining()); buffer.duplicate().get(bytes)
        val start = when {
            bytes.size >= 5 && bytes.take(4) == listOf<Byte>(0, 0, 0, 1) -> 4
            bytes.size >= 4 && bytes.take(3) == listOf<Byte>(0, 0, 1) -> 3
            else -> error("Encoder CSD is not Annex B")
        }
        check(bytes[start].toInt() and 31 == type)
        return bytes.copyOfRange(start, bytes.size)
    }
}
