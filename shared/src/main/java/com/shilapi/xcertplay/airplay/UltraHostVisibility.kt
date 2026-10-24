// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

/**
 * Per-connection mailbox for real host callbacks. Updating it never enters the VM or waits for a
 * composition. The panel worker consumes the latest state at its next transaction boundary.
 * Host visibility supplies neither local vehicle scene state nor the iPhone's focus response.
 */
internal class UltraHostVisibility {
    private var closed = false
    private var foreground = false
    private var pending: Boolean? = false

    /** Pass a complete host snapshot; window focus alone is not host visibility. */
    @Synchronized fun update(resumed: Boolean, surfaceReady: Boolean) {
        if (closed) return
        val next = resumed && surfaceReady
        if (next != foreground) {
            foreground = next
            pending = next
        }
    }

    /** Only the owning panel's serialized worker consumes this mailbox. */
    @Synchronized fun takePending(): Boolean? = pending.also { pending = null }

    /** Late callbacks from the retired connection cannot re-arm its state. */
    @Synchronized fun close() {
        closed = true
        pending = null
    }
}
