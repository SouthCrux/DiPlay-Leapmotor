// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.ultra

import android.graphics.Bitmap
import com.shilapi.xcertplay.media.VideoCompositionFrame
import com.shilapi.xcertplay.airplay.UltraReferenceFrameDrawer
import com.shilapi.xcertplay.airplay.UltraLocalSettingsFrame
import com.shilapi.xcertplay.airplay.UltraLocalSettingsFrames
import com.shilapi.xcertplay.airplay.UltraLocalSettingsInput
import com.shilapi.xcertplay.airplay.UltraSceneUnavailableException
import com.shilapi.xcertplay.airplay.AirPlayContact
import org.json.JSONObject
import kotlin.math.abs

/** Selected reference bridge output only; frame pixels remain borrowed until this call returns. */
object UltraReferenceCenterDraw {
    /** The loader is pinned to one immutable resource snapshot, never shared across connections. */
    class Session(private val loadPng: (String) -> ByteArray,
        override val localSettingsFrames: UltraLocalSettingsFrames? = null,
    ) : UltraReferenceFrameDrawer {
        private val renderer = UltraSceneRenderer(textureCacheBytes = 16 * 1024 * 1024)
        private val touch = UltraVehiclePanelTouchRouter { token, x, y ->
            localSettingsFrames?.tap(token, x, y) == true
        }
        override fun outputSubmitted(bitmap: Bitmap) = touch.submitted(bitmap)
        override fun outputInvalidated() = touch.invalidate()
        override fun handleTouch(contacts: List<AirPlayContact>, canceled: Boolean): Boolean = touch.touch(contacts,canceled)
        override fun draw(bytes: ByteArray, offered: VideoCompositionFrame, current: VideoCompositionFrame?): Bitmap =
            drawWithSettings(bytes, offered, current, null, null)
        override fun drawWithSettings(bytes: ByteArray, offered: VideoCompositionFrame, current: VideoCompositionFrame?,
            offeredSettings: UltraLocalSettingsFrame?, currentSettings: UltraLocalSettingsFrame?): Bitmap =
            drawWith(renderer, bytes, offered, current, loadPng, offeredSettings, currentSettings,
                onDrawn = touch::drawn)
        override fun close() { touch.invalidate(); renderer.close() }
    }

    fun draw(
        bytes: ByteArray,
        offered: VideoCompositionFrame,
        current: VideoCompositionFrame?,
        loadPng: (String) -> ByteArray,
    ): Bitmap = UltraSceneRenderer().use { drawWith(it, bytes, offered, current, loadPng) }

    private fun drawWith(renderer: UltraSceneRenderer, bytes: ByteArray, offered: VideoCompositionFrame,
        current: VideoCompositionFrame?, loadPng: (String) -> ByteArray,
        offeredSettings: UltraLocalSettingsFrame? = null, currentSettings: UltraLocalSettingsFrame? = null,
        onDrawn: ((Bitmap, JSONObject) -> Unit)? = null): Bitmap {
        require(bytes.size <= 1024 * 1024) { "Reference scene size limit" }
        val report = JSONObject(String(bytes, Charsets.UTF_8))
        check(report.get("fixture") == true && report.get("fullUltraUI") == false &&
            report.get("incompleteBridgeOverride") == true) { "Reference bridge report required" }
        val scene = report.getJSONObject("scene")
        if (scene.opt("rootUnavailable") == true) throw UltraSceneUnavailableException()
        return renderer.render(scene, loadPng,
            UltraVideoFrameSource { identifier, token, pts ->
                if (identifier == UltraLocalSettingsInput.IDENTIFIER) {
                    val frame = when (token) {
                        offeredSettings?.token -> checkNotNull(offeredSettings)
                        currentSettings?.token -> checkNotNull(currentSettings)
                        else -> error("Reference scene selected a stale Settings frame")
                    }
                    check(frame.senderNanos >= 0 && abs(pts - frame.senderNanos / 1_000_000_000.0) <= 0.000001) {
                        "Reference Settings origin differs"
                    }
                    check(!frame.bitmap.isRecycled && frame.bitmap.width == UltraLocalSettingsInput.WIDTH &&
                        frame.bitmap.height == UltraLocalSettingsInput.HEIGHT) { "Invalid Settings pixels" }
                    frame.bitmap
                } else {
                check(identifier == "iPhoneVideo") { "Unsupported reference video input" }
                val frame = when (token) {
                    offered.token -> offered
                    current?.token -> checkNotNull(current)
                    else -> error("Reference scene selected a stale frame")
                }
                check(abs(pts - frame.senderNanos / 1_000_000_000.0) <= 0.000001) {
                    "Reference video origin differs"
                }
                frame.bitmap
                }
            }).also { onDrawn?.invoke(it, scene) }
    }
}
