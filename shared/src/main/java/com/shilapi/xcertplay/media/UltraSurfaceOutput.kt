// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Surface
import java.io.Closeable

/**
 * Explicit output for a selected Ultra layout. Borrows host surfaces; never releases them.
 * Replacement/detach is serialized with drawing, so a destroy callback can wait for Canvas release.
 * Dimensions must match the selected layout; this path never changes normal CarPlay geometry.
 */
class UltraSurfaceOutput internal constructor(private val canvasAccess: CanvasAccess) : Closeable {
    constructor() : this(AndroidCanvasAccess)
    enum class Result { WAITING, NO_SURFACE, LOCKING, LOCK_FAILED, DRAWING, SIZE_MISMATCH, POSTING, POST_FAILED, SUBMITTED }
    @Volatile var lastResult = Result.WAITING
        private set

    internal interface CanvasAccess {
        fun lock(surface: Surface): Canvas
        fun post(surface: Surface, canvas: Canvas)
    }
    private object AndroidCanvasAccess : CanvasAccess {
        override fun lock(surface: Surface): Canvas = surface.lockCanvas(null)
        override fun post(surface: Surface, canvas: Canvas) = surface.unlockCanvasAndPost(canvas)
    }
    private var surface: Surface? = null
    private var closed = false

    @Synchronized fun attach(next: Surface) {
        check(!closed) { "Ultra output closed" }
        surface = next
    }

    /** Ignore a late destroy callback from a replaced host surface. */
    @Synchronized fun detach(previous: Surface) {
        if (surface === previous) surface = null
    }

    /** A successful return establishes Canvas submission, not physical presentation. */
    @Synchronized fun publish(bitmap: Bitmap): Boolean {
        val output = surface?.takeIf { !closed && it.isValid } ?: run { lastResult = Result.NO_SURFACE; return false }
        // Surface validity is only a snapshot: the window manager may abandon or resize the
        // buffer between this check and lock/post during camera and Activity transitions.
        // A missing submission is retried by the live pipeline, bounded by the host watchdog.
        lastResult = Result.LOCKING
        val canvas = try {
            canvasAccess.lock(output)
        } catch (_: IllegalArgumentException) {
            lastResult = Result.LOCK_FAILED
            return false
        } catch (_: Surface.OutOfResourcesException) {
            lastResult = Result.LOCK_FAILED
            return false
        }
        var submitted = false
        try {
            if (canvas.width == bitmap.width && canvas.height == bitmap.height) {
                lastResult = Result.DRAWING
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                submitted = true
            } else lastResult = Result.SIZE_MISMATCH
        } finally {
            try {
                if (submitted) lastResult = Result.POSTING
                canvasAccess.post(output, canvas)
            } catch (_: IllegalArgumentException) {
                lastResult = Result.POST_FAILED
                submitted = false
            } catch (_: Surface.OutOfResourcesException) {
                lastResult = Result.POST_FAILED
                submitted = false
            }
        }
        if (submitted) lastResult = Result.SUBMITTED
        return submitted
    }

    @Synchronized override fun close() { closed = true; surface = null }
}
