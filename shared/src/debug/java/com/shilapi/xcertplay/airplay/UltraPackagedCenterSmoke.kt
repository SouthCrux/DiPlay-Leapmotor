// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.shilapi.xcertplay.media.VideoCompositionFrame
import com.shilapi.xcertplay.media.VideoCompositionSmoke
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

/** Actual APK assets/host/decoder/Canvas, with a simulated peer and offscreen output only. */
@androidx.annotation.RequiresApi(26)
object UltraPackagedCenterSmoke {
    private const val PANEL = "Center_Display"
    private const val LAYOUT = "9E19BC4E-5251-5821-A0BA-26479A79F722"

    fun run(args: Array<String>,
        draw: (ByteArray, VideoCompositionFrame, VideoCompositionFrame?, (String) -> ByteArray) -> Bitmap,
        createDrawer: (((String) -> ByteArray) -> UltraReferenceFrameDrawer)? = null,
    ) {
        require(args.size == 3) { "LIBRARY APK FRESH_CACHE_DIR required" }
        if (Looper.getMainLooper() == null) {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
        }
        val library = File(args[0]).canonicalFile
        val apk = File(args[1]).canonicalFile
        require(library.isFile && apk.isFile)
        val cache = File(args[2]).absoluteFile
        require(!cache.exists() && cache.mkdir()) { "Cache directory must be fresh" }
        val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val imageThread = HandlerThread("diplay-ultra-packaged-pixels").apply { start() }
        val failure = AtomicReference<Throwable?>()
        val hostFailed = CountDownLatch(1)
        val completed = CopyOnWriteArrayList<CountDownLatch>()
        val readers = mutableListOf<ImageReader>()
        var host: UltraReferenceCenterHost? = null
        var renderer: UltraSessionRenderer? = null
        val drawingOwners = AtomicInteger()
        val drawingRetired = AtomicInteger()
        // Explicit simulated provider: neither these numbers nor source loss come from a car.
        val batteryReads = AtomicInteger()
        val simulatedBattery = AtomicReference<VehicleStatusSnapshot?>(VehicleStatusSnapshot(
            rangeKm = 160, rangeWarning = null, batteryPercent = 75.0,
            currentChargeWh = null, maxChargeWh = null, maxRangeKm = null, charging = null))
        val vehicleSource = VehicleStatusProvider {
            batteryReads.incrementAndGet()
            simulatedBattery.get()
        }
        try {
            val cookie = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                .invoke(assetManager, apk.absolutePath) as Int
            check(cookie != 0) { "APK assets could not be attached" }
            val context = object : ContextWrapper(null) {
                override fun getApplicationContext(): Context = this
                override fun getAssets(): AssetManager = assetManager
                override fun getNoBackupFilesDir(): File = cache
            }
            val prepared = checkNotNull(UltraReferenceCenterHost.prepareWithDrawer(context, { load ->
                val delegate = createDrawer?.invoke(load) ?: object : UltraReferenceFrameDrawer {
                    override fun draw(bytes: ByteArray, offered: VideoCompositionFrame, current: VideoCompositionFrame?): Bitmap =
                        draw(bytes, offered, current, load)
                    override fun close() = Unit
                }
                drawingOwners.incrementAndGet()
                object : UltraReferenceFrameDrawer {
                    override fun draw(bytes: ByteArray, offered: VideoCompositionFrame, current: VideoCompositionFrame?): Bitmap =
                        try { delegate.draw(bytes, offered, current) }
                        catch (error: Throwable) { failure.compareAndSet(null, error); throw error }
                    override fun close() { delegate.close(); drawingRetired.incrementAndGet() }
                }
            }, { System.load(library.absolutePath) }, vehicleSource)) { "No packaged reference selection" }
            check(batteryReads.get() == 0) { "Preparation read the live provider before a session worker existed" }
            host = prepared
            fun reportFailure() {
                failure.compareAndSet(null, IllegalStateException("Packaged center host failed"))
                hostFailed.countDown()
                completed.forEach { it.countDown() }
            }
            fun reader(): Pair<ImageReader, CountDownLatch> {
                val done = CountDownLatch(1)
                completed += done
                val input = ImageReader.newInstance(1920, 720, PixelFormat.RGBA_8888, 3)
                readers += input
                input.setOnImageAvailableListener({ source ->
                    try {
                        source.acquireLatestImage()?.use { image -> verifyQuadrants(image); done.countDown() }
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error); done.countDown()
                    }
                }, Handler(imageThread.looper))
                return input to done
            }
            val (first, firstPixels) = reader()
            val firstAttachment = prepared.attachHost(::reportFailure)
            firstAttachment.updateSurface(first.surface, 1920, 720)
            firstAttachment.updateResumed(true)
            val selected = prepared.renderer()
            renderer = selected
            val protocol = selected.panel(PANEL)
            val peer = SimulatedPeer(protocol)
            peer.reset()
            peer.applyLayout(requireRequest = true)
            VideoCompositionSmoke.feedGeneratedVideo(selected.videoSink, 1920, 720)
            check(firstPixels.await(20, TimeUnit.SECONDS)) { "No packaged-host output pixels" }
            failure.get()?.let { throw it }
            check(hostFailed.count == 1L)
            check(batteryReads.get() > 0) { "Packaged pipeline did not consult its explicit battery provider" }

            // Exercise the real pipeline's missing-source input, not an injected native success.
            // The separate JNI contract fixture verifies CP INVALIDVALUE and unit conversion.
            simulatedBattery.set(null)
            val beforeLoss = batteryReads.get()
            Thread.sleep(1_100)
            peer.poll(1)
            check(batteryReads.get() > beforeLoss) { "Packaged pipeline did not recheck its missing source" }
            failure.get()?.let { throw it }

            // The same connection survives host pause and adopts a different borrowed output.
            firstAttachment.updateResumed(false)
            firstAttachment.detach(first.surface)
            peer.poll(10)
            val (second, secondPixels) = reader()
            val nextAttachment = prepared.attachHost(::reportFailure)
            nextAttachment.updateSurface(second.surface, 1920, 720)
            nextAttachment.updateResumed(true)
            firstAttachment.detach(first.surface)
            firstAttachment.updateResumed(false)
            firstAttachment.close()
            peer.applyLayout(requireRequest = false)
            VideoCompositionSmoke.feedGeneratedVideo(selected.videoSink, 1920, 720, originNanos = 12_000_000_000L)
            check(secondPixels.await(20, TimeUnit.SECONDS)) { "Adopted host received no composed pixels" }
            failure.get()?.let { throw it }
            nextAttachment.updateResumed(false)
            nextAttachment.detach(second.surface)
            nextAttachment.close()
            selected.close(); renderer = null
            prepared.close(); host = null
            awaitResourceRetirement(cache)
            val drawingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (drawingRetired.get() != drawingOwners.get() && System.nanoTime() < drawingDeadline) Thread.sleep(10)
            check(drawingOwners.get() == 1 && drawingRetired.get() == 1) { "Drawing owner did not retire exactly once" }
            val readsAfterRetirement = batteryReads.get()
            runCatching { protocol.poll() }
            check(batteryReads.get() == readsAfterRetirement) { "Retired session still queried the vehicle source" }
            check(first.surface.isValid && second.surface.isValid) { "Host released borrowed reader surfaces" }
        } finally {
            renderer?.close()
            host?.close()
            try { awaitResourceRetirement(cache) } finally {
                readers.forEach(ImageReader::close)
                imageThread.quitSafely(); imageThread.join(5_000)
                assetManager.close()
                check(!imageThread.isAlive) { "Pixel reader worker did not retire" }
            }
            val ownerParent = File(cache, "ultra-resource-sessions")
            if (ownerParent.exists()) {
                val registry = File(ownerParent, ".registry.lock")
                if (registry.exists()) check(registry.delete())
                check(ownerParent.delete()) { "Packaged resource parent still owns input" }
            }
            check(cache.delete()) { "Smoke cache was not empty" }
        }
    }

    private fun verifyQuadrants(image: Image) {
        check(image.width == 1920 && image.height == 720)
        val plane = image.planes.single()
        fun rgb(x: Int, y: Int): List<Int> {
            val offset = y * plane.rowStride + x * plane.pixelStride
            check(plane.pixelStride >= 4)
            return (0..2).map { plane.buffer.get(offset + it).toInt() and 255 }
        }
        val red = rgb(480, 180); val green = rgb(1440, 180)
        val blue = rgb(480, 540); val white = rgb(1440, 540)
        check(red[0] > 180 && red[1] < 100 && red[2] < 100) { "Red quadrant differs: $red" }
        check(green[1] > 180 && green[0] < 100 && green[2] < 100) { "Green quadrant differs: $green" }
        check(blue[2] > 180 && blue[0] < 100 && blue[1] < 100) { "Blue quadrant differs: $blue" }
        check(white.all { it > 180 }) { "White quadrant differs: $white" }
    }

    private fun awaitResourceRetirement(cache: File) {
        val parent = File(cache, "ultra-resource-sessions")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (parent.listFiles()?.any { it.name.startsWith("ultra-session-") } == true) {
            check(System.nanoTime() < deadline) { "Packaged runtime resources did not retire" }
            CountDownLatch(1).await(25, TimeUnit.MILLISECONDS)
        }
    }

    private class SimulatedPeer(private val protocol: UiSyncPanelSession) {
        private val replies = mutableListOf<Map<String, Any?>>()
        private var currentSsn: Long? = null
        private var pendingReset: Map<String, Any?>? = null
        private var resetCompleted = false
        private var sequence = 1L
        private fun collect(messages: List<ByteArray>) {
            for (bytes in messages) {
                val message = checkNotNull(BoundedPlist.decode(bytes))
                val ssn = message["ssn"] as? Long ?: error("Original message has no session sequence")
                if (message["type"] == 0L && (currentSsn == null || ssn > checkNotNull(currentSsn))) {
                    // Foregrounding during bootstrap can reset the original session again. A queued
                    // earlier reset is not the current session, and cannot be acknowledged as one.
                    currentSsn = ssn
                    pendingReset = message
                    sequence = 1L
                    resetCompleted = false
                    replies.clear()
                }
                if (ssn == currentSsn) {
                    replies += message
                    if (message["type"] == 1L) resetCompleted = true
                }
            }
        }
        private fun receive(bytes: ByteArray) {
            collect(protocol.receive(bytes))
        }
        fun poll(count: Int) {
            repeat(count) { collect(protocol.poll()) }
        }
        fun reset() {
            poll(1)
            ensureReady()
        }
        private fun ensureReady() {
            repeat(32) {
                pendingReset?.let { selected ->
                    pendingReset = null
                    receive(BplistCodec.encode(mapOf("type" to 1L, "ssn" to selected.getValue("ssn"),
                        "psn" to 1L, "asn" to selected.getValue("psn"), "version" to 2L, "id" to "fixture-peer")))
                }
                poll(1)
                if (resetCompleted && pendingReset == null) return
            }
            error("Original reset-complete missing for current session $currentSsn")
        }
        fun applyLayout(requireRequest: Boolean) {
            poll(10)
            ensureReady()
            val currentReplies = replies.filter { it["ssn"] == currentSsn }
            val requests = currentReplies.filter { it["type"] == 2L }.mapNotNull {
                (it["data"] as? ByteArray)?.let(BoundedPlist::decode)
            }
            val requested = requests.any {
                it["type"] == 5L && (it["data"] as? Map<*, *>)?.get("layoutID") == LAYOUT
            }
            if (requireRequest) check(requested) { "Original Wallpaper request missing" }
            if (requested) {
                val selectedSsn = checkNotNull(currentSsn)
                val acknowledged = currentReplies.mapNotNull { it["psn"] as? Long }.max()
                val commands = listOf(9L to mapOf("isTransitioning" to true), 3L to mapOf("layoutID" to LAYOUT),
                    6L to mapOf("layoutID" to LAYOUT, "regions" to emptyList<Any>()), 9L to mapOf("isTransitioning" to false))
                for (command in commands) {
                    check(currentSsn == selectedSsn && pendingReset == null) { "Original session reset during layout transition" }
                    sequence++
                    receive(BplistCodec.encode(mapOf("type" to 2L, "ssn" to selectedSsn,
                        "psn" to sequence, "asn" to acknowledged,
                        "data" to BplistCodec.encode(mapOf("type" to command.first, "data" to command.second)))))
                    poll(1)
                }
            }
            replies.clear()
        }
    }
}
