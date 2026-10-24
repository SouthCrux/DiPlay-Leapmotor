package com.shilapi.xcertplay.airplay

import android.graphics.Bitmap
import com.shilapi.xcertplay.media.TimedVideoFrame
import java.io.Closeable

/** Explicit kind-1 input. Configuring this requires a real connection-owned producer. */
object UltraLocalSettingsInput {
    const val IDENTIFIER = "LeapmotorVehicleSettings"
    const val WIDTH = 1650
    const val HEIGHT = 720
}

/**
 * Immutable 1650x720 pixels borrowed by drawing until close; a distinct lease transfers to the panel
 * owner. Tokens and PTS are local to this input and monotonic for the whole connection.
 */
interface UltraLocalSettingsFrame : TimedVideoFrame {
    val bitmap: Bitmap
}

/** Read-only, connection-owned source, called on the existing composition worker. Never self-starts. */
interface UltraLocalSettingsFrames : Closeable {
    /** A new owned lease, or null for unchanged content. Missing data must be drawn as unavailable. */
    fun nextFrame(phoneSenderNanos: Long): UltraLocalSettingsFrame?
    /** Force fresh delivery without resetting tokens/PTS; called on a worker, never a host callback. */
    fun invalidate()
    /** Exact submitted image token and image-local coordinates. No vehicle writes. */
    fun tap(frameToken: Long, x: Float, y: Float): Boolean = false
}
