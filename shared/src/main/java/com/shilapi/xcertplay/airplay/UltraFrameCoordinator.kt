// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.media.TimedVideoFrame
import java.io.Closeable

/** One atomic offer/tick/snapshot/commit transaction. A return never proves physical presentation. */
internal class UltraFrameCoordinator<T : TimedVideoFrame>(
    private val runtime: UltraNativeRuntime,
    input: String,
) : Closeable {
    private val phone = Slot<T>(input)
    private val settings = Slot<UltraLocalSettingsFrame>(UltraLocalSettingsInput.IDENTIFIER)
    private var closed = false

    init { require(input.matches(Regex("[A-Za-z0-9_.-]{1,128}")) && input != UltraLocalSettingsInput.IDENTIFIER) }

    private inner class Slot<F : TimedVideoFrame>(private val input: String) {
        var current: F? = null
            private set
        var pending: F? = null
            private set
        private var lastToken = 0L
        private var lastPts = 0L
        private var started = false

        /** Owns the lease even if its offer fails or is rejected. */
        fun offer(frame: F): Boolean {
            check(frame !== current) { "Current frame cannot be offered twice" }
            if (frame.token <= lastToken || frame.senderNanos < lastPts) { frame.close(); return false }
            check(pending == null)
            pending = frame
            event("offer", frame.token, frame.senderNanos / 1_000_000_000.0)
            lastToken = frame.token; lastPts = frame.senderNanos
            started = true
            return true
        }

        /** True when the script retired pixels that the producer may still consider delivered. */
        fun afterTick(): Boolean {
            if (!started) return false
            val state = event("state")
            if (state["flushed"] == true) {
                val discarded = pending != null || current != null
                release(); started = false
                return discarded
            }
            pending?.let { frame ->
                check(state["pendingToken"] == frame.token) { "Pending frame changed" }
                if (state["deferred"] == true) {
                    event("cancel", frame.token)
                    pending = null
                    frame.close()
                    return true
                }
            }
            return false
        }

        fun commit() {
            val frame = pending ?: return
            event("commit", frame.token)
            pending = null
            val previous = current
            current = frame
            previous?.close()
        }

        fun cancel() {
            val frame = pending ?: return
            event("cancel", frame.token)
            pending = null
            frame.close()
        }

        fun clear() {
            if (started) event("clear")
            release()
            started = false
        }

        fun release() {
            val offered = pending; val retained = current
            pending = null; current = null
            try { offered?.close() } finally { retained?.close() }
        }

        private fun event(action: String, token: Long? = null, pts: Double? = null): Map<*, *> {
            val fields = linkedMapOf<String, Any>("action" to action, "input" to input)
            token?.let { fields["token"] = it }; pts?.let { fields["pts"] = it }
            return runtime.request(7, BplistCodec.encode(fields)) as? Map<*, *> ?: error("Frame state missing")
        }
    }

    /** Existing single-input path remains inactive for Settings. */
    fun <R : Any> compose(frame: T, draw: (ByteArray, T, T?) -> R, discard: (R) -> Unit): R? =
        composeWithSettings(frame, null, { bytes, offered, current, _, _ -> draw(bytes, offered, current) }, discard)

    /** Owns both leases; Settings has its own token/PTS watermark and cannot replace the phone clock. */
    @Synchronized fun <R : Any> composeWithSettings(
        frame: T, local: UltraLocalSettingsFrame?,
        draw: (ByteArray, T, T?, UltraLocalSettingsFrame?, UltraLocalSettingsFrame?) -> R,
        discard: (R) -> Unit,
        onSettingsDiscarded: () -> Unit = {},
    ): R? {
        if (closed) { try { frame.close() } finally { local?.close() }; return null }
        var phoneClaimed = false
        var unclaimedLocal = local
        var output: R? = null
        try {
            return runtime.exclusive {
                phoneClaimed = true
                if (!phone.offer(frame)) {
                    unclaimedLocal = null
                    local?.close()
                    if (local != null) onSettingsDiscarded()
                    return@exclusive null
                }
                if (local != null) { unclaimedLocal = null; settings.offer(local) }
                runtime.request(2, byteArrayOf(1))
                phone.afterTick()
                val settingsDiscarded = settings.afterTick()
                val offered = phone.pending
                if (offered == null) {
                    settings.cancel()
                    if (local != null || settingsDiscarded) onSettingsDiscarded()
                    return@exclusive null
                }
                if (settingsDiscarded || local != null && settings.pending !== local) onSettingsDiscarded()
                val bytes = runtime.request(3) as? ByteArray ?: error("Scene missing")
                val rendered = try { draw(bytes, offered, phone.current, settings.pending, settings.current) }
                catch (_: UltraSceneUnavailableException) {
                    phone.cancel()
                    settings.cancel()
                    if (local != null) onSettingsDiscarded()
                    return@exclusive null
                }
                output = rendered
                phone.commit(); settings.commit()
                output = null
                rendered
            }
        } catch (failure: Throwable) {
            output?.let { runCatching { discard(it) } }
            if (!phoneClaimed && frame !== phone.current) runCatching { frame.close() }
            unclaimedLocal?.takeIf { it !== settings.current && it !== settings.pending }
                ?.let { runCatching { it.close() } }
            close()
            throw failure
        }
    }

    /** Called at a serialized pause boundary; retire native tokens before releasing their pixels. */
    @Synchronized fun clearSettings() {
        if (closed) return
        try { runtime.exclusive { settings.clear() } }
        catch (failure: Throwable) { close(); throw failure }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { phone.release() } finally { try { settings.release() } finally { runtime.close() } }
    }
}
