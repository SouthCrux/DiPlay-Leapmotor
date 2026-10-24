// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import java.io.Closeable

/** Keeps one decoded image so host/protocol changes can redraw without another codec callback. */
internal class RetainedVideoFrameWorker(
    private val consume: (VideoCompositionFrame) -> Unit,
    onError: () -> Unit,
) : Closeable {
    // Owned by the worker until it has exited; every delivery has an independent pixel lease.
    private var retained: VideoCompositionFrame? = null
    private val worker = LatestVideoFrameWorker<VideoCompositionFrame>({ frame ->
        if (retained?.let { frame.senderNanos < it.senderNanos } == true) frame.close()
        else {
            retained?.close()
            retained = frame
            deliver()
        }
    }, onError, ::deliver)

    private fun deliver() {
        val delivery = retained?.retainForComposition() ?: return
        try { consume(delivery) } catch (failure: Throwable) { delivery.close(); throw failure }
    }

    fun offer(frame: VideoCompositionFrame) = worker.offer(frame)
    fun requestRefresh() = worker.requestRefresh()
    override fun close() = worker.close()

    /** Release the retained pixels only after the last composition has finished borrowing them. */
    @Synchronized fun awaitClosed(timeoutMillis: Long): Boolean {
        if (!worker.awaitClosed(timeoutMillis)) return false
        retained?.close()
        retained = null
        return true
    }
}
