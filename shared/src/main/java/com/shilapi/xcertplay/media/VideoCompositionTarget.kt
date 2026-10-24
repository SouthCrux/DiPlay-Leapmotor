// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import android.graphics.Bitmap
import android.view.Surface
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Optional decoder destination for composition. The decoder must render to this exact [surface].
 * Register origin before releasing a codec buffer; a SurfaceTexture callback can arrive immediately.
 * The returned positive token becomes the buffer's surface timestamp, not a presentation deadline.
 * Only non-display consumers may implement this contract. Detach the decoder before closing one.
 */
interface VideoCompositionTarget {
    val surface: Surface
    fun queueFrame(senderNanos: Long): Long
    fun discardFrame(token: Long)
}

/** A latched, copied frame with an exact origin association. Ownership transfers to the consumer. */
interface TimedVideoFrame : Closeable {
    val token: Long
    val senderNanos: Long
}

class VideoCompositionFrame private constructor(
    override val token: Long,
    override val senderNanos: Long,
    private val pixels: Pixels,
) : TimedVideoFrame {
    internal constructor(token: Long, senderNanos: Long, pixels: Bitmap, onClosed: () -> Unit = {}) :
        this(token, senderNanos, Pixels(pixels, onClosed))
    private val closed = AtomicBoolean()
    val bitmap: Bitmap get() { check(!closed.get()) { "Frame closed" }; return pixels.bitmap }

    /** A new delivery of the same decoded pixels/phone time, without another copy or codec buffer. */
    @Synchronized internal fun retainForComposition(): VideoCompositionFrame {
        check(!closed.get()) { "Frame closed" }
        val delivery = VideoSurfaceOrigins.newToken()
        pixels.retain()
        return VideoCompositionFrame(delivery, senderNanos, pixels)
    }

    @Synchronized override fun close() { if (closed.compareAndSet(false, true)) pixels.release() }

    private class Pixels(val bitmap: Bitmap, private val onClosed: () -> Unit) {
        private var references = 1
        @Synchronized fun retain() { check(references > 0); references++ }
        @Synchronized fun release() {
            check(references > 0)
            if (--references == 0) try { bitmap.recycle() } finally { onClosed() }
        }
    }
}

/** Unique surface timestamps prevent ambiguous joins after drops, decoder changes or late callbacks. */
internal class VideoSurfaceOrigins(private val capacity: Int = 32) : Closeable {
    companion object {
        private const val TOKEN_LIMIT = 9007199254740992L // Luau's exact integer range.
        private val nextToken = AtomicLong(1)
        internal fun newToken(): Long {
            val token = nextToken.getAndUpdate { if (it == TOKEN_LIMIT) it else it + 1 }
            check(token in 1 until TOKEN_LIMIT) { "Surface token exhausted" }
            return token
        }
    }
    private val origins = linkedMapOf<Long, Long>()
    private var closed = false
    init { require(capacity in 1..128) }

    @Synchronized fun queue(senderNanos: Long): Long {
        check(!closed)
        require(senderNanos > 0)
        val token = newToken()
        if (origins.size == capacity) origins.remove(origins.keys.first())
        origins[token] = senderNanos
        return token
    }

    @Synchronized fun discard(token: Long) { origins.remove(token) }

    /** SurfaceTexture latches the latest queued buffer; older tokens can no longer become current. */
    @Synchronized fun latched(token: Long): Long? {
        if (closed) return null
        val origin = origins.remove(token) ?: return null
        origins.keys.removeAll { it < token }
        return origin
    }

    @Synchronized override fun close() { closed = true; origins.clear() }
}
