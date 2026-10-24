// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import java.io.Closeable
import android.graphics.Bitmap
import com.shilapi.xcertplay.media.VideoCompositionFrame

/** Connection-owned drawing resources. Close only after its composition worker has drained. */
interface UltraReferenceFrameDrawer : Closeable {
    val localSettingsFrames: UltraLocalSettingsFrames? get() = null
    /** Called only after this Canvas was submitted and its host activation revalidated. */
    fun outputSubmitted(bitmap: Bitmap) {}
    /** No Canvas/VM work here: can run during a host lifecycle callback. */
    fun outputInvalidated() {}
    /** Normalized full-canvas gesture; true consumes the complete sequence locally. */
    fun handleTouch(contacts: List<AirPlayContact>, canceled: Boolean = false): Boolean = false
    fun draw(bytes: ByteArray, offered: VideoCompositionFrame, current: VideoCompositionFrame?): Bitmap
    fun drawWithSettings(bytes: ByteArray, offered: VideoCompositionFrame, current: VideoCompositionFrame?,
                         offeredSettings: UltraLocalSettingsFrame?, currentSettings: UltraLocalSettingsFrame?): Bitmap {
        check(offeredSettings == null && currentSettings == null) { "Local Settings drawing is not implemented" }
        return draw(bytes, offered, current)
    }
}

/** Optional renderer owned by exactly one AirPlay control session; never shared across connections. */
internal interface UltraSessionRenderer : Closeable {
    val videoSink: MediaSink
    fun panel(id: String): UiSyncPanelSession
}
