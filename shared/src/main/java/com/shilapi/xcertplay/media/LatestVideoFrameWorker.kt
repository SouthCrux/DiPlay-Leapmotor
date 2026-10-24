// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import java.io.Closeable

/** One active and one pending frame; a slow compositor never creates an unbounded callback queue. */
class LatestVideoFrameWorker<T : TimedVideoFrame>(
    private val consume: (T) -> Unit,
    private val onError: () -> Unit = {},
    private val refresh: () -> Unit = {},
) : Closeable {
    private val lock = Object()
    private var pending: T? = null
    private var closed = false
    private var refreshPending = false
    private val worker = Thread({
        while (true) {
            val frame = synchronized(lock) {
                while (!closed && pending == null && !refreshPending) lock.wait()
                if (closed) return@Thread
                pending.also { pending = null; if (it == null) refreshPending = false }
            }
            try { if (frame != null) consume(frame) else refresh() } catch (_: Throwable) {
                runCatching { frame?.close() }
                close(); runCatching(onError)
                return@Thread
            }
        }
    }, "diplay-ultra-composition").apply { isDaemon = true; start() }

    /** Takes ownership; a successful consumer must retain or close the frame itself. */
    fun offer(frame: T) {
        val discarded = synchronized(lock) {
            if (closed) frame else pending.also { pending = frame; lock.notifyAll() }
        }
        discarded?.close()
    }

    /** Coalesced with other refreshes and serialized after any pending decoded frame. */
    fun requestRefresh() = synchronized(lock) {
        if (!closed) { refreshPending = true; lock.notifyAll() }
    }

    override fun close() {
        val discarded = synchronized(lock) {
            if (closed) return
            closed = true
            pending.also { pending = null; lock.notifyAll() }
        }
        discarded?.close()
    }

    /** An active transaction completes before this returns true. */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis in 1..30_000)
        check(Thread.currentThread() !== worker)
        worker.join(timeoutMillis)
        return !worker.isAlive
    }
}
