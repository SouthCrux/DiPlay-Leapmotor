package com.shilapi.xcertplay.media

/**
 * Decoder release timing, not confirmation that a frame appeared on a physical display.
 * [senderNanos] retains the phone's clock. [scheduledLocalNanos] is on System.nanoTime.
 */
data class VideoFrameRelease(
    val senderNanos: Long,
    val scheduledLocalNanos: Long,
    /** Exact timestamp assigned to the released Surface buffer, when known. Not proof of latching. */
    val surfaceTimestampNanos: Long? = null,
)

/** Bounded origin lookup for reordered decoder output. Guarded by the decoder output lock. */
internal class VideoFrameOrigin(private val capacity: Int = 64) {
    init { require(capacity in 1..256) }
    private data class Entry(var senderNanos: Long?, var pending: Long = 1)
    // Null marks a collision or absent phone clock. Keep collisions until all outputs are consumed.
    private val origins = linkedMapOf<Long, Entry>()
    private var discardedThrough = Long.MIN_VALUE

    fun queued(presentationUs: Long, senderNanos: Long) {
        if (presentationUs <= discardedThrough) return
        val previous = origins[presentationUs]
        if (previous != null) {
            previous.senderNanos = null
            if (previous.pending < Long.MAX_VALUE) previous.pending++
        } else origins[presentationUs] = Entry(senderNanos.takeIf { it > 0 })
        if (origins.size > capacity) {
            val oldest = origins.keys.first()
            discardedThrough = maxOf(discardedThrough, oldest)
            origins.remove(oldest)
        }
    }

    fun take(presentationUs: Long): Long? {
        val entry = origins[presentationUs] ?: return null
        if (entry.pending > 1) { entry.pending--; return null }
        origins.remove(presentationUs)
        return entry.senderNanos.takeIf { presentationUs > discardedThrough }
    }
    fun clear() { origins.clear(); discardedThrough = Long.MIN_VALUE }
}
