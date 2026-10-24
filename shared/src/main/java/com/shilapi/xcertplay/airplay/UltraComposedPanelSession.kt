// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.media.TimedVideoFrame
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One panel owns protocol and composition together. Construct a fresh instance for each connection.
 * Calls run on transport/composition workers, never on the UI or decoder callback thread.
 * The caller must retire its decoder before closing its video destination and drain its frame worker.
 * Closing the router also retires the retained frame and VM; late frames are consumed and dropped.
 * This owner is optional: normal application startup does not register it.
 */
internal class UltraComposedPanelSession<T : TimedVideoFrame>(
    private val runtime: UltraNativeRuntime,
    input: String,
    sceneInputMode: UltraSceneInputMode = UltraSceneInputMode.DISABLED,
    batteryProvider: (() -> UltraVehicleBatteryState?)? = null,
    private val localSettings: UltraLocalSettingsFrames? = null,
) : UiSyncPanelSession {
    private val protocol = UltraScriptPanelSession(runtime)
    private val frames = UltraFrameCoordinator<T>(runtime, input)
    private val visibility = UltraHostVisibility()
    private val sceneLifecycle = if (sceneInputMode == UltraSceneInputMode.EXPLICIT_SERVICE) UltraVehicleSceneLifecycle() else null
    private val centerPolicy = if (sceneInputMode == UltraSceneInputMode.REFERENCE_CENTER_ACCESSORY) UltraCenterScenePolicy() else null
    private var centerForeground = false
    private var hostForeground = false
    private val invalidateSettings = AtomicBoolean(false)
    private val battery = batteryProvider?.let { UltraVehicleBatteryLifecycle(it) }
    private val defaultPublicationToken = Any()
    private var closed = false

    /** Safe for host callbacks: no panel lock, script execution or frame work on the caller. */
    fun updateHostVisibility(resumed: Boolean, surfaceReady: Boolean) {
        if (!resumed || !surfaceReady) invalidateSettings.set(true)
        if (centerPolicy != null) centerPolicy.updateHostVisibility(resumed, surfaceReady)
        else visibility.update(resumed, surfaceReady)
    }

    fun requestAccessoryScene(request: Boolean) {
        if (!request) invalidateSettings.set(true)
        checkNotNull(centerPolicy) { "Reference center scene policy not selected" }.requestAccessoryScene(request)
    }

    fun allowsPublication(): Boolean = centerPolicy?.allowsPublication() ?: true

    fun publicationToken(): Any? = if (centerPolicy != null) centerPolicy.publicationToken() else defaultPublicationToken

    fun allowsPublication(token: Any?): Boolean = if (centerPolicy != null) centerPolicy.allowsPublication(token)
        else token === defaultPublicationToken

    fun updateVehicleScene(state: UltraVehicleSceneState?) = checkNotNull(sceneLifecycle) {
        "Vehicle scene service not selected"
    }.update(state)

    private fun applyLocalState() {
        if (invalidateSettings.getAndSet(false) && localSettings != null) {
            frames.clearSettings()
            localSettings.invalidate()
        }
        battery?.takePending()?.let { runtime.request(9, it.payload()) }
        if (centerPolicy != null) {
            centerPolicy.takePending()?.let {
                runtime.request(5, byteArrayOf(if (it.foreground) 1 else 0))
                runtime.request(8, byteArrayOf(it.scene.wireValue))
                centerForeground = it.foreground
            }
            return
        }
        visibility.takePending()?.let {
            runtime.request(5, byteArrayOf(if (it) 1 else 0))
            hostForeground = it
        }
        sceneLifecycle?.takePending()?.let { runtime.request(8, byteArrayOf(it.state?.wireValue ?: 0xff.toByte())) }
    }

    @Synchronized override fun receive(bytes: ByteArray): List<ByteArray> = guarded {
        // Keep message delivery, script scheduling and draining in one session transaction.
        runtime.exclusive { applyLocalState(); protocol.receive(bytes) }
    }

    @Synchronized override fun poll(): List<ByteArray> = guarded {
        runtime.exclusive { applyLocalState(); protocol.poll() }
    }

    /** Takes ownership even after close. Outputs transfer only after successful native commit. */
    fun <R : Any> compose(
        frame: T,
        draw: (ByteArray, T, T?) -> R,
        discard: (R) -> Unit,
    ): R? = composeWithSettings(frame, { bytes, offered, current, offeredSettings, currentSettings ->
        check(offeredSettings == null && currentSettings == null) { "Local Settings drawing is not implemented" }
        draw(bytes, offered, current)
    }, discard)

    /** Both inputs share one transaction; only a phone frame advances the presentation clock. */
    @Synchronized fun <R : Any> composeWithSettings(
        frame: T,
        draw: (ByteArray, T, T?, UltraLocalSettingsFrame?, UltraLocalSettingsFrame?) -> R,
        discard: (R) -> Unit,
    ): R? {
        if (closed) {
            frame.close()
            return null
        }
        return guarded {
            var transferred = false
            try {
                runtime.exclusive {
                    applyLocalState()
                    if (centerPolicy != null && !centerForeground) {
                        transferred = true
                        frame.close()
                        return@exclusive null
                    }
                    val local = if (localSettings != null && (centerPolicy?.allowsPublication() ?: hostForeground))
                        localSettings.nextFrame(frame.senderNanos) else null
                    transferred = true
                    frames.composeWithSettings(frame, local, draw, discard,
                        onSettingsDiscarded = { localSettings?.invalidate() })
                }
            } catch (failure: Throwable) {
                // The coordinator takes ownership only after the lifecycle request succeeds.
                if (!transferred) frame.close()
                throw failure
            }
        }
    }

    private inline fun <R> guarded(block: () -> R): R {
        check(!closed) { "Ultra panel session closed" }
        try { return block() } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        visibility.close()
        sceneLifecycle?.close()
        centerPolicy?.close()
        battery?.close()
        try { frames.close() } finally { try { localSettings?.close() } finally { runtime.close() } }
    }
}
