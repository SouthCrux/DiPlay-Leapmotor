package com.shilapi.xcertplay.airplay

/** Structural observations only: never retain peer strings, identifiers or message bodies. */
internal class UiSyncProbeDiagnostics {
    private data class Shape(
        val panelIndex: Int,
        val type: Long,
        val command: Long?,
        val readyBefore: Boolean,
        val readyAfter: Boolean,
        val replies: Int,
    )
    private val seen = mutableSetOf<Shape>()

    /** At most 32 distinct observations per channel, including malformed/unknown command kinds. */
    fun record(bytes: ByteArray, panelIndex: Int, readyBefore: Boolean, readyAfter: Boolean, replies: Int): String? {
        if (seen.size >= MAX_OBSERVATIONS || panelIndex < 0 || replies < 0) return null
        val frame = Opack.decode(bytes) as? Opack.Dict ?: return null
        val header = (frame["data"] as? Opack.Data)?.bytes()?.let(BoundedPlist::decode) ?: return null
        val type = header["type"] as? Long ?: return null
        val command = if (type == 2L) (header["data"] as? ByteArray)?.let(BoundedPlist::decode)?.get("type") as? Long else null
        val shape = Shape(panelIndex, type, command, readyBefore, readyAfter, replies)
        if (!seen.add(shape)) return null
        return "ultra handshake: UI Sync observation panelIndex=$panelIndex type=$type " +
            "command=${command ?: "none"} readyBefore=$readyBefore readyAfter=$readyAfter " +
            "replies=$replies renderer=false" +
            if (seen.size == MAX_OBSERVATIONS) " observationLimitReached=true" else ""
    }

    private companion object { const val MAX_OBSERVATIONS = 32 }
}
