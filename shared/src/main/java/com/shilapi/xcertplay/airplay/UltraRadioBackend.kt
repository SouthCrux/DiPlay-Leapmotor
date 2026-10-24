// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import java.io.Closeable

/**
 * Explicit handoff from an authorized receiver. No platform service is acquired automatically.
 * The currently verified wire description is DAB only; an FM tuner cannot be relabelled as DAB.
 * Cached getters and command dispatch/cancellation must never block on tuner I/O.
 */
interface UltraRadioBackend {
    enum class CommandKind { PLAY, PAUSE, NEXT, PREVIOUS, TUNE_IDENTIFIER }
    data class Capabilities(val commands: Set<CommandKind>)
    data class Station(val identifier: String, val name: String, val shortName: String, val ensemble: String)
    data class Snapshot(
        /** Actual authorized receiver observation, in the same elapsed-nanosecond clock as the owner. */
        val observedAtNanos: Long,
        val available: Boolean,
        val stations: List<Station>,
        val currentIdentifier: String?,
        val playing: Boolean,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
    )
    data class Command(val kind: CommandKind, val identifier: String? = null)
    /** Null unless the source and DAB semantics have been independently verified for this vehicle. */
    fun verifiedDabCapabilities(): Capabilities?
    /** Null when the source is missing, permission was withdrawn or the last observation expired. */
    fun snapshot(): Snapshot?
    /** True only after a real receiver observation confirms the operation, never merely after dispatch. */
    fun request(command: Command, complete: (Boolean) -> Unit): Closeable
}
