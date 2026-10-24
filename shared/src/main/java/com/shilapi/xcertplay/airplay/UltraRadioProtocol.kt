// SPDX-License-Identifier: GPL-3.0-only
// Wire reference: f-io/LIVI 4bcd1335, livi-cp/src/vehicle_state.rs media/now_playing/radio_controls.
package com.shilapi.xcertplay.airplay

import android.os.SystemClock
import com.shilapi.xcertplay.airplay.Opack.Array
import com.shilapi.xcertplay.airplay.Opack.Bool
import com.shilapi.xcertplay.airplay.Opack.Data
import com.shilapi.xcertplay.airplay.Opack.Dict
import com.shilapi.xcertplay.airplay.Opack.Integer
import com.shilapi.xcertplay.airplay.Opack.Text
import com.shilapi.xcertplay.airplay.Opack.Value
import com.shilapi.xcertplay.airplay.Opack.dict
import java.io.Closeable

/**
 * Isolated optional radio module. It neither changes /info nor installs a global Vehicle State owner.
 * A future session aggregator must merge these accessories with its other services before advertising.
 * Capability/source loss retires this owner; a recovered receiver needs a fresh session description.
 */
internal class UltraRadioProtocol(
    private val backend: UltraRadioBackend? = null,
    private val elapsedNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val maxAgeNanos: Long = 5_000_000_000L,
    private val commandTimeoutNanos: Long = 5_000_000_000L,
) : Closeable {
    data class Message(val lane: VehicleStatePrototype.Lane, val bytes: ByteArray)
    sealed interface Poll {
        data class Messages(val messages: List<Message>) : Poll
        data object SourceLost : Poll
    }
    private val capabilities = runCatching { backend?.verifiedDabCapabilities()?.let {
        UltraRadioBackend.Capabilities(it.commands.toSet())
    } }.getOrNull()
    private var closed = false
    private val subscribed = mutableSetOf<Long>()
    private var lastValues = emptyMap<Long, Value>()
    private val replies = mutableListOf<Message>()
    private class Pending(val lane: VehicleStatePrototype.Lane, val transaction: Value,
        val iid: Long, val command: UltraRadioBackend.Command, val started: Long,
        val previousIdentifier: String?) {
        var cancellation: Closeable? = null
    }
    private var pending: Pending? = null
    init { require(maxAgeNanos > 0 && commandTimeoutNanos > 0) }

    /** Empty without an authorized, verified, fresh DAB source. No default station/state is invented. */
    @Synchronized fun accessories(): Array {
        val snapshot = current() ?: return Array(emptyList())
        val values = values(snapshot)
        fun characteristic(iid: Long, type: Long, format: Long, fixed: Boolean = false,
            maximum: Long? = null): Dict {
            val metadata = mutableListOf<Pair<Value, Value>>(
                Text("iid") to Integer(iid), Text("type") to Integer(type), Text("format") to Integer(format),
                Text("mutable") to Bool(!fixed), Text("writable") to Bool(false),
                Text("oemWritable") to Bool(true), Text("priority") to Integer(1))
            if (format == 10L) metadata += Text("maximumLength") to Integer(65535)
            if (format == 13L) metadata += listOf(Text("maximumLength") to Integer(2_147_483_647),
                Text("largePayload") to Bool(true))
            maximum?.let { metadata += listOf(Text("minimumValue") to Integer(0),
                Text("maximumValue") to Integer(it), Text("stepValue") to Integer(1)) }
            values[iid]?.let { metadata += Text("initialValue") to it }
            return Dict(metadata)
        }
        val media = dict("iid" to Integer(4096), "type" to Integer(0x03000001), "version" to Text("1.0"),
            "services" to Array(listOf(dict("iid" to Integer(4097), "type" to Integer(0x14000006),
                "characteristics" to Array(listOf(
                    characteristic(4098, 0x30000019, 10, fixed = true),
                    characteristic(4099, 0x32000025, 1, fixed = true),
                    characteristic(4100, 0x32000011, 0), characteristic(4101, 0x30000001, 10),
                    characteristic(4102, 0x32000026, 10), characteristic(4103, 0x32000012, 13)))))))
        val nowPlayingEntries = dict("iid" to Integer(4353), "type" to Integer(0x14000001),
            "characteristics" to Array(listOf(
                characteristic(4354, 0x32000023, 10), characteristic(4355, 0x32000003, 10),
                characteristic(4356, 0x32000004, 10), characteristic(4357, 0x32000005, 10),
                characteristic(4359, 0x32000034, 1)))).entries.toMutableList()
        val commands = capabilities!!.commands.map(::control).sortedBy { (it["iid"] as Integer).value }
        if (commands.isNotEmpty()) nowPlayingEntries += Text("controls") to Array(commands)
        return Array(listOf(media, dict("iid" to Integer(4352), "type" to Integer(0x0F000001),
            "version" to Text("1.0"), "services" to Array(listOf(Dict(nowPlayingEntries))))))
    }

    @Synchronized fun handle(lane: VehicleStatePrototype.Lane, bytes: ByteArray): List<Message> {
        if (closed) return emptyList()
        val outer = Opack.decode(bytes) as? Dict ?: return emptyList()
        if (outer["pluginID"] != Integer(0)) return emptyList()
        val body = (outer["pluginData"] as? Data)?.bytes()?.let(Opack::decode) as? Dict ?: return emptyList()
        val transaction = body["transactionID"] ?: Opack.Null
        if (transaction !is Integer && transaction !is Opack.Uuid && transaction != Opack.Null) return emptyList()
        val command = (body["command"] as? Text)?.value ?: return emptyList()
        if (command == "configRequest") return listOf(message(lane, dict("command" to Text("configResponse"),
            "transactionID" to transaction, "values" to dict("accessories" to accessories()))))
        val snapshot = current() ?: run { close(); return emptyList() }
        val values = values(snapshot)
        return when (command) {
            "registerRequest" -> {
                val requested = (body["values"] as? Array)?.values ?: return emptyList()
                if (requested.any { it !is Integer } || requested.size > 32) return emptyList()
                val held = mutableListOf<Pair<Value, Value>>()
                val missing = mutableListOf<Pair<Value, Value>>()
                requested.distinct().forEach { key ->
                    val iid = (key as Integer).value
                    if (iid in READ_IIDS) subscribed += iid
                    values[iid]?.let { held += key to it; lastValues = lastValues + (iid to it) }
                        ?: run { missing += key to Integer(NO_VALUE) }
                }
                val response = dict("command" to Text("registerResponse"), "transactionID" to transaction,
                    "values" to Dict(held)).entries + if (missing.isEmpty()) emptyList() else listOf(Text("errors") to Dict(missing))
                listOf(message(lane, Dict(response)))
            }
            "writeRequest" -> {
                val entries = (body["values"] as? Dict)?.entries ?: return emptyList()
                if (entries.any { it.first !is Integer } || entries.size > 32) return emptyList()
                listOf(message(lane, dict("command" to Text("writeResponse"), "transactionID" to transaction,
                    "errors" to Dict(entries.map { it.first to Integer(NO_VALUE) }))))
            }
            "controlRequest" -> controlRequest(lane, transaction, body["values"], snapshot)
            else -> emptyList()
        }
    }

    @Synchronized fun poll(): Poll {
        val snapshot = current() ?: run { close(); return Poll.SourceLost }
        val now = elapsedNanos()
        pending?.takeIf { now < it.started || now - it.started >= commandTimeoutNanos }?.let { finish(it, false) }
        val output = replies.toMutableList()
        replies.clear()
        val current = values(snapshot)
        if (subscribed.any { it in lastValues && it !in current }) { close(); return Poll.SourceLost }
        val changed = subscribed.mapNotNull { iid -> current[iid]?.takeIf { it != lastValues[iid] }?.let { Integer(iid) to it } }
        lastValues = current
        if (changed.isNotEmpty()) output += message(VehicleStatePrototype.Lane.HIGH,
            dict("command" to Text("updateNotify"), "values" to Dict(changed)))
        return Poll.Messages(output)
    }

    private fun controlRequest(lane: VehicleStatePrototype.Lane, transaction: Value,
        input: Value?, snapshot: UltraRadioBackend.Snapshot): List<Message> {
        val entries = (input as? Dict)?.entries ?: return emptyList()
        if (entries.any { it.first !is Integer } || entries.size > 32) return emptyList()
        // Single-flight actuator policy: do not partially dispatch an ambiguous multi-control batch.
        val entry = entries.singleOrNull()
        val command = entry?.let { parseControl((it.first as Integer).value, it.second, snapshot) }
        if (entry == null || command == null || pending != null || replies.size >= 16)
            return listOf(controlResponse(lane, transaction, entries.map { (it.first as Integer).value }, false))
        val request = Pending(lane, transaction, (entry.first as Integer).value, command, elapsedNanos(), snapshot.currentIdentifier)
        pending = request
        try {
            val cancellation = backend!!.request(command) { applied -> synchronized(this) {
                val actual = current()
                val observed = actual != null && actual.observedAtNanos > request.started && when (command.kind) {
                    UltraRadioBackend.CommandKind.PLAY -> actual.playing
                    UltraRadioBackend.CommandKind.PAUSE -> !actual.playing
                    UltraRadioBackend.CommandKind.TUNE_IDENTIFIER -> actual.currentIdentifier == command.identifier
                    UltraRadioBackend.CommandKind.NEXT, UltraRadioBackend.CommandKind.PREVIOUS ->
                        actual.currentIdentifier != null && actual.currentIdentifier != request.previousIdentifier
                }
                finish(request, applied && observed)
            } }
            if (pending === request) request.cancellation = cancellation else runCatching { cancellation.close() }
        } catch (_: Exception) { finish(request, false) }
        return emptyList()
    }

    private fun finish(request: Pending, success: Boolean) {
        if (closed || pending !== request) return
        pending = null
        replies += controlResponse(request.lane, request.transaction, listOf(request.iid), success)
        runCatching { request.cancellation?.close() }
    }
    private fun controlResponse(lane: VehicleStatePrototype.Lane, transaction: Value, ids: List<Long>, success: Boolean): Message =
        message(lane, dict("command" to Text("controlResponse"), "transactionID" to transaction,
            (if (success) "values" else "errors") to Dict(ids.map { Integer(it) to if (success) Opack.Null else Integer(NO_COMMAND) })))

    private fun parseControl(iid: Long, params: Value, snapshot: UltraRadioBackend.Snapshot): UltraRadioBackend.Command? {
        val kind = COMMAND_IDS.entries.firstOrNull { it.value == iid }?.key ?: return null
        if (kind !in capabilities!!.commands) return null
        if (kind == UltraRadioBackend.CommandKind.TUNE_IDENTIFIER) {
            val dict = params as? Dict ?: return null
            if (dict.entries.map { it.first }.toSet() != setOf(Text("identifier"), Text("sourceIdentifier"))) return null
            val id = (dict["identifier"] as? Text)?.value ?: return null
            if (dict["sourceIdentifier"] != Text(SOURCE) || snapshot.stations.none { it.identifier == id }) return null
            return UltraRadioBackend.Command(kind, id)
        }
        if (params != Opack.Null && (params !is Dict || params.entries.isNotEmpty())) return null
        return UltraRadioBackend.Command(kind)
    }
    private fun current(): UltraRadioBackend.Snapshot? = runCatching {
        if (closed || capabilities == null) return null
        val source = backend ?: return null
        if (source.verifiedDabCapabilities() != capabilities) return null
        val s = source.snapshot() ?: return null
        val now = elapsedNanos()
        if (!s.available || s.observedAtNanos <= 0 || s.observedAtNanos > now || now - s.observedAtNanos > maxAgeNanos ||
            s.stations.size !in 1..128 || s.stations.map { it.identifier }.distinct().size != s.stations.size) return null
        fun text(value: String) = value.length <= 256 && value.toByteArray(Charsets.UTF_8).size <= 256 &&
            value.none { it.isISOControl() }
        if (s.stations.any { it.identifier.isEmpty() || !text(it.identifier) || !text(it.name) || !text(it.shortName) || !text(it.ensemble) } ||
            listOfNotNull(s.title, s.artist, s.album).any { !text(it) } ||
            (s.currentIdentifier != null && s.stations.none { it.identifier == s.currentIdentifier }) ||
            (s.playing && s.currentIdentifier == null)) return null
        s.copy(stations = s.stations.toList())
    }.getOrNull()

    private fun values(s: UltraRadioBackend.Snapshot): Map<Long, Value> = buildMap {
        put(4098, Text(SOURCE)); put(4099, Integer(6)); put(4100, Bool(false)); put(4101, Text("DAB+"))
        s.currentIdentifier?.let { put(4102, Text(it)) }
        put(4103, Array(s.stations.map { station -> dict("identifier" to Text(station.identifier),
            "mediaItemName" to Text(station.name), "mediaItemShortName" to Text(station.shortName),
            "mediaItemType" to Text("dab"), "ensemble" to Text(station.ensemble), "mediaItemCategory" to Integer(0)) }))
        put(4354, Text(SOURCE)); s.title?.let { put(4355, Text(it)) }; s.artist?.let { put(4356, Text(it)) }
        s.album?.let { put(4357, Text(it)) }; put(4359, Integer(if (s.playing) 1 else 0))
    }
    private fun control(kind: UltraRadioBackend.CommandKind): Dict {
        val iid = COMMAND_IDS.getValue(kind)
        val type = when (kind) {
            UltraRadioBackend.CommandKind.PLAY -> 0x0F000032L
            UltraRadioBackend.CommandKind.PAUSE -> 0x0F000033L
            UltraRadioBackend.CommandKind.NEXT -> 0x0F000035L
            UltraRadioBackend.CommandKind.PREVIOUS -> 0x0F000036L
            UltraRadioBackend.CommandKind.TUNE_IDENTIFIER -> 0x0F00004BL
        }
        val entries = dict("iid" to Integer(iid), "type" to Integer(type), "sender" to Integer(0), "hasResponse" to Bool(true)).entries
        return Dict(entries + if (kind != UltraRadioBackend.CommandKind.TUNE_IDENTIFIER) emptyList() else listOf(
            Text("requestParameters") to Array(listOf("identifier", "sourceIdentifier").map { dict("name" to Text(it), "format" to Integer(10)) })))
    }
    private fun message(lane: VehicleStatePrototype.Lane, inner: Dict) = Message(lane,
        Opack.encode(dict("pluginID" to Integer(0), "pluginData" to Data(Opack.encode(inner)))))

    /** Session aggregator hooks: its single register/update owner subscribes to these values. */
    @Synchronized fun observedValues(): Map<Long, Value>? = current()?.let(::values)
    fun knowsReadIid(iid: Long): Boolean = iid in READ_IIDS
    @Synchronized fun reset() {
        val previous = pending; pending = null
        subscribed.clear(); lastValues = emptyMap(); replies.clear()
        runCatching { previous?.cancellation?.close() }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        reset()
    }
    companion object {
        private const val SOURCE = "DAB"
        private const val NO_VALUE = -6727L
        private const val NO_COMMAND = -6735L
        private val READ_IIDS = setOf(4098L, 4099L, 4100L, 4101L, 4102L, 4103L, 4354L, 4355L, 4356L, 4357L, 4359L)
        private val COMMAND_IDS = mapOf(UltraRadioBackend.CommandKind.PLAY to 4360L, UltraRadioBackend.CommandKind.PAUSE to 4361L,
            UltraRadioBackend.CommandKind.NEXT to 4362L, UltraRadioBackend.CommandKind.PREVIOUS to 4363L,
            UltraRadioBackend.CommandKind.TUNE_IDENTIFIER to 4364L)
    }
}
