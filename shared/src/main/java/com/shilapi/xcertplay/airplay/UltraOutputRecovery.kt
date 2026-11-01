// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

/** A resumed output must receive an accepted Canvas even if the phone's video is static.
 * The existing protocol poll supplies bounded wakeups; this owns no timer, surface or pixels.
 */
internal class UltraOutputRecovery {
    private var visible = false
    private var awaitingOutput = true

    @Synchronized fun visibility(resumed: Boolean, surfaceReady: Boolean) {
        visible = resumed && surfaceReady
        if (!visible) awaitingOutput = true
    }

    @Synchronized fun invalidated() { awaitingOutput = true }
    @Synchronized fun submitted() { awaitingOutput = false }

    /** Protocol silence does not imply that the local scene/output is ready. */
    @Synchronized fun needsRefresh(protocolMessages: Boolean): Boolean =
        protocolMessages || visible && awaitingOutput
}
