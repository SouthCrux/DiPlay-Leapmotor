package com.shilapi.xcertplay.vehicle

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** C11 event 4114 contains ASCII '1'/'0', not Android key codes. Main-thread confined. */
internal class LeapmotorVoiceButton(
    private val eligible: () -> Boolean,
    private val single: () -> Unit,
    private val double: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var startedAt: Long? = null
    private var lastAt = -1L
    private var lastPress = -1L
    private var down = false
    private var sawRelease = false
    private var firstFreshPress = -1L
    private var pending = false
    private val fireSingle = Runnable {
        pending = false
        if (startedAt != null && eligible()) single()
    }

    fun start() { stop(); startedAt = SystemClock.elapsedRealtime() }

    fun stop() {
        startedAt = null
        lastAt = -1L
        lastPress = -1L
        down = false
        sawRelease = false
        firstFreshPress = -1L
        pending = false
        handler.removeCallbacks(fireSingle)
    }

    fun receive(payload: ByteArray?, receivedAt: Long) {
        val start = startedAt ?: return
        if (payload == null && receivedAt == -1L) { start(); return }
        val now = SystemClock.elapsedRealtime()
        // Discard replay/queued notifications during subscription and stale IPC delivery.
        if (receivedAt < start || receivedAt > now || now - receivedAt > 500L || receivedAt < lastAt) return
        lastAt = receivedAt
        val pressed = when {
            payload?.contentEquals(byteArrayOf(49)) == true -> true
            payload?.contentEquals(byteArrayOf(48)) == true -> false
            else -> return
        }
        val wasDown = down
        down = pressed
        // A cached initial press must never open Siri, even if native initialization was slow.
        if (!sawRelease) {
            if (pressed && !wasDown && receivedAt - start >= 1_000L) firstFreshPress = receivedAt
            if (!pressed) {
                sawRelease = true
                // A live, complete click after startup is sufficient; the OEM does not always
                // publish an idle release when we subscribe. Still reject a cached/held press.
                if (wasDown && firstFreshPress >= 0) acceptPress(firstFreshPress)
                firstFreshPress = -1L
            }
            return
        }
        if (receivedAt - start < 1_000L || !pressed || wasDown) return
        acceptPress(receivedAt)
    }

    private fun acceptPress(receivedAt: Long) {
        if (lastPress >= 0 && receivedAt - lastPress < 80L) return
        lastPress = receivedAt
        if (!eligible()) {
            pending = false
            handler.removeCallbacks(fireSingle)
            return
        }
        if (pending) {
            pending = false
            handler.removeCallbacks(fireSingle)
            double()
        } else {
            pending = true
            handler.postDelayed(fireSingle, 350L)
        }
    }
}
