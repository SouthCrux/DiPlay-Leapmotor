// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

/** Mutually exclusive local scene owners; no mode is selected by ordinary CarPlay startup. */
internal enum class UltraSceneInputMode { DISABLED, EXPLICIT_SERVICE, REFERENCE_CENTER_ACCESSORY }

/**
 * Explicit local UI policy for the selected reference center Wallpaper only. ACCESSORY_MODE here
 * requests its accessory UI; it does not report ignition, gear or driving state. The caller must
 * select the verified resource/layout contract separately; this is not package/phone acceptance.
 * A request is connection-local. Resume/surface callbacks alone cannot create one.
 */
internal class UltraCenterScenePolicy {
    data class Snapshot(val foreground: Boolean, val scene: UltraVehicleSceneState)
    private var requested = false
    private var resumed = false
    private var surfaceReady = false
    private var closed = false
    private var current = Snapshot(false, UltraVehicleSceneState.NONE)
    private var pending: Snapshot? = current
    private var publicationGeneration = Any()

    @Synchronized fun requestAccessoryScene(request: Boolean) {
        if (closed) return
        requested = request
        refresh()
    }

    @Synchronized fun updateHostVisibility(resumed: Boolean, surfaceReady: Boolean) {
        if (closed) return
        this.resumed = resumed
        this.surfaceReady = surfaceReady
        refresh()
    }

    private fun refresh() {
        val active = requested && resumed && surfaceReady
        val next = Snapshot(active, if (active) UltraVehicleSceneState.ACCESSORY_MODE else UltraVehicleSceneState.NONE)
        if (next != current) {
            current = next; pending = next
            publicationGeneration = Any()
        }
    }

    /** Consumed at one VM transaction boundary, so foreground and scene cannot mix snapshots. */
    @Synchronized fun takePending(): Snapshot? = pending.also { pending = null }

    /** Current eligibility only; completed outputs must also match their activation token. */
    @Synchronized fun allowsPublication(): Boolean = !closed && current.foreground

    @Synchronized fun publicationToken(): Any? = if (!closed && current.foreground) publicationGeneration else null

    /** A pause/resume during a composition must not admit that old output into the new activation. */
    @Synchronized fun allowsPublication(token: Any?): Boolean =
        !closed && current.foreground && token != null && token === publicationGeneration

    @Synchronized fun close() {
        closed = true
        requested = false
        pending = null
    }
}
