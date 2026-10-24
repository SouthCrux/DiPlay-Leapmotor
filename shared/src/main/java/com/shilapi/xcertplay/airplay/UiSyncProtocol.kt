// SPDX-License-Identifier: GPL-3.0-only
// Protocol reference: f-io/LIVI 4bcd1335, livi-cp/src/ui_sync.rs.
package com.shilapi.xcertplay.airplay

/**
 * Conservative per-panel diagnostic state: without a compositor this can complete reset only.
 * Its optional apply gate is a local probe policy. The original script protocol separately ACKs
 * receipt and schedules drawing by video PTS; an ACK must never be interpreted as presentation.
 */
internal class UiSyncProtocol(
    private val panel: String,
    private val apply: ((Choice) -> Boolean)? = null,
) {
    sealed interface Choice {
        data class Layout(val id: String, val palette: String?) : Choice
        data class Palette(val layout: String, val palette: String) : Choice
        data class Appearance(val preference: Int) : Choice
        data object Focus : Choice
    }
    data class Layout(val id: String, val palette: String, val active: Map<String, String> = emptyMap())
    private var session = 0L
    private var nextTx = 1L
    private var lastRx = 0L
    private var awaiting = false
    var ready = false
        private set
    val rendererAvailable: Boolean get() = apply != null
    private var phoneId: Any? = null
    private var wanted: Layout? = null

    fun reopen() { session = 0; nextTx = 1; lastRx = 0; awaiting = false; ready = false; phoneId = null }

    fun select(layout: Layout): List<ByteArray> {
        require(identifier(layout.id) && identifier(layout.palette) && layout.active.size <= 64 &&
            layout.active.all { identifier(it.key) && identifier(it.value) })
        wanted = layout.copy(active = layout.active.toMap())
        return if (ready && rendererAvailable) listOf(message(2, request(layout))) else emptyList()
    }

    fun handle(bytes: ByteArray): List<ByteArray> {
        val frame = Opack.decode(bytes) as? Opack.Dict ?: return emptyList()
        if (frame["displayUUID"] != Opack.Text(panel)) return emptyList()
        val header = (frame["data"] as? Opack.Data)?.bytes()?.let(BoundedPlist::decode) ?: return emptyList()
        val type = header["type"] as? Long ?: return emptyList()
        val ssn = header["ssn"] as? Long ?: return emptyList()
        val psn = header["psn"] as? Long ?: return emptyList()
        if (psn <= 0 || (header["noAck"] != null && header["noAck"] !is Boolean)) return emptyList()
        if (type == 0L) {
            if (awaiting && nextTx == 2L) return emptyList()
            val id = header["id"]
            if (id != null && id !is String && id !is Long && id !is ByteArray) return emptyList()
            if ((id is String && id.length > 128) || (id is ByteArray && id.size > 128)) return emptyList()
            if (session == Long.MAX_VALUE) return emptyList()
            session++; nextTx = 1; lastRx = 0; ready = false; awaiting = true; phoneId = id
            return listOf(message(0, extra = withId(mapOf("version" to 2L))))
        }
        if (ssn != session) return emptyList()
        val asn = header["asn"] as? Long
        if (asn != null && asn >= nextTx) return emptyList()
        if (type == 1L && awaiting) {
            val version = (header["version"] as? Long ?: 1L).coerceAtMost(2)
            if (version < 1) return emptyList()
            awaiting = false; ready = true; lastRx = psn
            val fields = mapOf("version" to version)
            val out = mutableListOf(message(1, extra = if (version >= 2) withId(fields) else fields))
            if (rendererAvailable) {
                out += message(2, mapOf("type" to 0x11L, "data" to mapOf("allow" to true)))
                wanted?.let { out += message(2, request(it)) }
                out += message(2, focus())
            }
            return out
        }
        if (!ready) return emptyList()
        if (type == 3L) { lastRx = maxOf(lastRx, psn); return emptyList() }
        if (type != 2L) return emptyList()
        if (psn <= lastRx) return if (psn == lastRx && header["noAck"] != true) listOf(message(3)) else emptyList()
        val command = (header["data"] as? ByteArray)?.let(BoundedPlist::decode) ?: return emptyList()
        val data = command["data"] as? Map<*, *> ?: return emptyList()
        fun text(key: String): String? = (data[key] as? String)?.takeIf(::identifier)
        val choice = when (command["type"] as? Long) {
            3L -> Choice.Layout(text("layoutID") ?: return emptyList(), text("paletteID"))
            0x10L -> Choice.Palette(text("layoutIdentifier") ?: return emptyList(), text("paletteIdentifier") ?: return emptyList())
            0x13L -> Choice.Appearance((data["preference"] as? Long)?.takeIf { it in 0..2 }?.toInt() ?: return emptyList())
            8L -> Choice.Focus
            else -> return emptyList()
        }
        if (apply?.invoke(choice) != true) return emptyList()
        lastRx = psn
        return buildList {
            if (header["noAck"] != true) add(message(3))
            if (choice == Choice.Focus) add(message(2, focus()))
        }
    }

    private fun identifier(value: String): Boolean = value.isNotEmpty() && value.length <= 128 && value.none(Char::isISOControl)
    private fun withId(fields: Map<String, Any>): Map<String, Any> = fields + (phoneId?.let { mapOf("id" to it) } ?: emptyMap())
    private fun focus(): Map<String, Any> = mapOf("type" to 7L, "data" to mapOf("tokenID" to 4711L))
    private fun request(layout: Layout): Map<String, Any> = mapOf("type" to 5L, "data" to mapOf(
        "layoutID" to layout.id, "paletteID" to layout.palette, "appearanceMode" to 1L,
        "fadeInNewLayout" to true, "fadeOutOldLayout" to true, "activeComponents" to layout.active,
    ))
    private fun message(type: Long, data: Map<String, Any>? = null, extra: Map<String, Any> = emptyMap()): ByteArray {
        check(nextTx < Long.MAX_VALUE)
        val header = linkedMapOf<String, Any>("type" to type, "ssn" to session, "psn" to nextTx++, "asn" to lastRx)
        header.putAll(extra)
        data?.let { header["data"] = BplistCodec.encode(it) }
        return Opack.encode(Opack.dict("displayUUID" to Opack.Text(panel), "data" to Opack.Data(BplistCodec.encode(header))))
    }
}
