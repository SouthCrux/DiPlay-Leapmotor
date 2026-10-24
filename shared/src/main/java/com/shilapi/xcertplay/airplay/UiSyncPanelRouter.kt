// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import java.io.Closeable

/** A single owner of the inner UI Sync protocol for one panel. Calls are serialized off the UI thread. */
internal interface UiSyncPanelSession : Closeable {
    fun receive(bytes: ByteArray): List<ByteArray>
    fun poll(): List<ByteArray> = emptyList()
}

/** Routes the authenticated outer envelope without modifying the owner's inner protocol/ACK state. */
internal class UiSyncPanelRouter(
    private val panels: Set<String>,
    private val observer: ((String, Boolean, ByteArray) -> Unit)? = null,
    private val factory: (String) -> UiSyncPanelSession,
) : Closeable {
    private val sessions = linkedMapOf<String, UiSyncPanelSession>()
    private var closed = false

    /** Start an explicitly selected panel, allowing the original protocol owner to initiate reset. */
    @Synchronized fun start(panel: String): List<ByteArray> = guarded {
        require(panel in panels) { "Unadvertised panel" }
        wrap(panel, sessions.getOrPut(panel) { factory(panel) }.poll())
    }

    @Synchronized fun receive(bytes: ByteArray): List<ByteArray> = guarded {
        val frame = Opack.decode(bytes) as? Opack.Dict ?: return@guarded emptyList()
        val panel = (frame["displayUUID"] as? Opack.Text)?.value ?: return@guarded emptyList()
        if (panel !in panels) return@guarded emptyList()
        val data = (frame["data"] as? Opack.Data)?.bytes() ?: return@guarded emptyList()
        require(BoundedPlist.decode(data) != null) { "Invalid UI Sync payload" }
        observe(panel, false, data)
        wrap(panel, sessions.getOrPut(panel) { factory(panel) }.receive(data))
    }

    @Synchronized fun poll(): List<ByteArray> = guarded {
        sessions.flatMap { (panel, session) -> wrap(panel, session.poll()) }
    }

    private fun wrap(panel: String, messages: List<ByteArray>): List<ByteArray> {
        require(messages.size <= 64)
        var total = 0
        return messages.map { bytes ->
            total += bytes.size
            require(total <= Opack.MAX_BYTES && BoundedPlist.decode(bytes) != null)
            observe(panel, true, bytes)
            Opack.encode(Opack.dict("displayUUID" to Opack.Text(panel), "data" to Opack.Data(bytes)))
        }
    }

    private fun observe(panel: String, sent: Boolean, bytes: ByteArray) {
        // A diagnostic callback must neither mutate the protocol's bytes nor interrupt its owner.
        observer?.let { runCatching { it(panel, sent, bytes.copyOf()) } }
    }

    private inline fun <T> guarded(block: () -> T): T {
        check(!closed) { "UI Sync router closed" }
        try { return block() } catch (failure: Exception) { close(); throw failure }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        // Retire every owner even if one implementation fails during cleanup.
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }
}
