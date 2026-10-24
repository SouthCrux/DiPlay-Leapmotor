// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import android.graphics.Bitmap
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.RetainedVideoFrameWorker
import com.shilapi.xcertplay.media.SurfaceTextureVideoOutput
import com.shilapi.xcertplay.media.VideoCompositionFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit development pipeline for one panel/input and one connection. Construct off the UI thread.
 * Owns its decoder, GL input, composition worker and script VM. Audio remains in the engine's normal
 * sink. No resource discovery, layout selection or activation occurs here.
 * publish borrows an independent composed bitmap only until it returns; never retain that bitmap.
 */
internal class UltraVideoPipeline(
    panelId: String,
    input: String,
    width: Int,
    height: Int,
    runtime: UltraNativeRuntime,
    private val draw: (ByteArray, VideoCompositionFrame, VideoCompositionFrame?) -> Bitmap,
    private val publish: (Bitmap) -> Unit,
    private val onFailure: () -> Unit = {},
    sceneInputMode: UltraSceneInputMode = UltraSceneInputMode.DISABLED,
    private val retireDrawing: () -> Unit = {},
    batteryProvider: (() -> UltraVehicleBatteryState?)? = null,
    localSettings: UltraLocalSettingsFrames? = null,
    drawWithSettings: ((ByteArray, VideoCompositionFrame, VideoCompositionFrame?, UltraLocalSettingsFrame?, UltraLocalSettingsFrame?) -> Bitmap)? = null,
    private val interaction: UltraReferenceFrameDrawer? = null,
) : UltraReferenceConnection {
    private val settingsDraw = drawWithSettings.also {
        require(localSettings == null || it != null) { "Local Settings source requires its compositor" }
    }
    private val selectedPanel = panelId
    private val closing = AtomicBoolean()
    private val failureReported = AtomicBoolean()
    private val publicationLock = Any()
    private val progress = UltraRenderProgress()
    private val stopped = CountDownLatch(1)
    private val session = UltraComposedPanelSession<VideoCompositionFrame>(runtime, input, sceneInputMode, batteryProvider,
        localSettings = localSettings)
    private val worker = RetainedVideoFrameWorker(::composeFrame, ::failed)

    private fun composeFrame(frame: VideoCompositionFrame) {
        if (closing.get()) { frame.close(); return }
        val publication = session.publicationToken()
        if (publication == null) { frame.close(); return }
        progress.phase = UltraRenderProgress.Phase.COMPOSING
        progress.compositions.incrementAndGet()
        val composed = session.composeWithSettings(frame, { bytes, offered, current, offeredSettings, currentSettings ->
            progress.phase = UltraRenderProgress.Phase.DRAWING
            try {
                if (settingsDraw != null) settingsDraw.invoke(bytes, offered, current, offeredSettings, currentSettings)
                else draw(bytes, offered, current)
            } catch (missing: UltraSceneUnavailableException) {
                progress.unavailable.incrementAndGet()
                throw missing
            }
        }, Bitmap::recycle)
        composed?.let { result ->
            progress.composed.incrementAndGet()
            try { synchronized(publicationLock) {
                if (!closing.get() && session.allowsPublication(publication)) {
                    progress.phase = UltraRenderProgress.Phase.PUBLISHING
                    progress.publications.incrementAndGet()
                    publish(result)
                } else progress.stale.incrementAndGet()
            } }
            finally { result.recycle() }
        }
        progress.phase = UltraRenderProgress.Phase.IDLE
    }
    override fun diagnosticFields(): String = progress.fields()
    private fun requestRefresh() { progress.redraws.incrementAndGet(); worker.requestRefresh() }
    private val target: SurfaceTextureVideoOutput
    private val decoder: AndroidMediaSink
    private val protocol = object : UiSyncPanelSession {
        override fun receive(bytes: ByteArray): List<ByteArray> = protocolCall {
            session.receive(bytes).also { requestRefresh() }
        }
        override fun poll(): List<ByteArray> = protocolCall {
            session.poll().also { if (it.isNotEmpty()) requestRefresh() }
        }
        override fun close() = this@UltraVideoPipeline.close()
    }

    init {
        try {
            require(selectedPanel.isNotBlank())
            if (sceneInputMode == UltraSceneInputMode.REFERENCE_CENTER_ACCESSORY) {
                require(selectedPanel == "Center_Display" && input == "iPhoneVideo" && width == 1920 && height == 720) {
                    "Reference center policy requires its selected panel/input geometry"
                }
            }
            target = SurfaceTextureVideoOutput(width, height, { frame ->
                progress.decoded.incrementAndGet(); worker.offer(frame)
            }, ::failed)
        } catch (failure: Throwable) {
            worker.close(); session.close()
            throw failure
        }
        try {
            decoder = AndroidMediaSink(surface = target.surface, videoWidth = width, videoHeight = height,
                videoCompositionTargets = mapOf(STREAM_TYPE to target))
        } catch (failure: Throwable) {
            target.close(); worker.close(); session.close()
            throw failure
        }
    }

    override val videoSink: MediaSink get() = decoder

    override fun outputSubmitted(bitmap: Bitmap) { if (!closing.get()) interaction?.outputSubmitted(bitmap) }
    override fun outputInvalidated() { interaction?.outputInvalidated() }
    override fun handleTouch(contacts: List<AirPlayContact>, canceled: Boolean): Boolean =
        !closing.get() && interaction?.handleTouch(contacts,canceled) == true

    /** Caller reports real host state; VM application occurs later on a panel/frame worker. */
    override fun updateHostVisibility(resumed: Boolean, surfaceReady: Boolean) {
        if (!closing.get()) {
            session.updateHostVisibility(resumed, surfaceReady)
            if (resumed && surfaceReady) requestRefresh()
        }
    }

    /** Explicit service only; the provider must invalidate missing/stale data with null. */
    fun updateVehicleScene(state: UltraVehicleSceneState?) {
        if (!closing.get()) session.updateVehicleScene(state)
    }

    /** Only for the explicitly selected reference-center UI policy, never a vehicle-mode report. */
    override fun requestAccessoryScene(request: Boolean) {
        if (!closing.get()) session.requestAccessoryScene(request)
    }

    override fun panel(id: String): UiSyncPanelSession {
        check(!closing.get()) { "Ultra pipeline closed" }
        if (id != selectedPanel) {
            failed()
            throw IllegalArgumentException("Unsupported Ultra panel")
        }
        return protocol
    }

    private inline fun <T> protocolCall(block: () -> T): T {
        check(!closing.get()) { "Ultra pipeline closed" }
        try { return block() } catch (failure: Throwable) { failed(); throw failure }
    }

    private fun failed() {
        close()
        if (failureReported.compareAndSet(false, true)) runCatching(onFailure)
    }

    /** Nonblocking: the target stays alive until the decoder confirms actual codec release. */
    override fun close() {
        synchronized(publicationLock) { if (!closing.compareAndSet(false, true)) return }
        interaction?.outputInvalidated()
        worker.close()
        decoder.close()
        decoder.whenVideoReleased {
            Thread({
                // Do not free pixels or the input surface underneath an active transaction/codec.
                // A slow worker keeps ownership until it really exits; no unsafe timeout cleanup.
                while (!worker.awaitClosed(1_000)) { /* wait off all transport/UI workers */ }
                try { session.close() } finally { try { retireDrawing() } finally {
                    target.close()
                    while (!target.awaitClosed(1_000)) { /* GL teardown owns its own worker */ }
                    stopped.countDown()
                } }
            }, "diplay-ultra-retire").apply { isDaemon = true; start() }
        }
    }

    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis in 0..30_000)
        return stopped.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    companion object { const val STREAM_TYPE = 110 }
}
