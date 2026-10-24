// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

/** Values read from the selected reference controller's Enums.UISceneState. */
internal enum class UltraVehicleSceneState(val wireValue: Byte) {
    NONE(0), STARTUP(1), DRIVE(2), EXIT(3), ACCESSORY_MODE(4),
}

/**
 * Explicit local scene-service input, scoped to one connection. This is neither iPhone permission
 * nor host visibility. A future provider must supply validated local scene input and report
 * null when absent/stale; opening DiPlay or receiving video must never imply DRIVE/ACCESSORY_MODE.
 * No provider or mode inference is installed here. An explicit center UI policy is a separate owner.
 * The VM entry must declare the service first.
 */
internal class UltraVehicleSceneLifecycle {
    data class Update(val state: UltraVehicleSceneState?)
    private var closed = false
    private var current: UltraVehicleSceneState? = null
    private var pending: Update? = Update(null)

    @Synchronized fun update(state: UltraVehicleSceneState?) {
        if (!closed && state != current) {
            current = state
            pending = Update(state)
        }
    }

    @Synchronized fun takePending(): Update? = pending.also { pending = null }

    @Synchronized fun close() {
        closed = true
        pending = null
    }
}
