// SPDX-License-Identifier: GPL-3.0-only
// Protocol reference: f-io/LIVI, revision 4bcd1335, livi-cp/src/vehicle_state.rs.
package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.airplay.Opack.Array
import com.shilapi.xcertplay.airplay.Opack.Bool
import com.shilapi.xcertplay.airplay.Opack.Data
import com.shilapi.xcertplay.airplay.Opack.Dict
import com.shilapi.xcertplay.airplay.Opack.Integer
import com.shilapi.xcertplay.airplay.Opack.Text
import com.shilapi.xcertplay.airplay.Opack.Value
import com.shilapi.xcertplay.airplay.Opack.dict
import kotlin.math.roundToLong

/**
 * Experimental vehicle protocol, shared by both lanes of the session runtime.
 * The reference trip-computer range service and explicitly verified temperature zones are described. Its IIDs are local instance IDs,
 * not C11 property IDs. No automatic HVAC capability, SOC schema, speed or synthetic readings are advertised.
 * The channel owner must share one instance between both lanes and close both on SourceLost.
 */
internal class VehicleStatePrototype(
    private val source: VehicleStatusProvider,
    private val climate: UltraClimateBackend? = null,
    radio: UltraRadioBackend? = null,
    radioElapsedNanos: () -> Long = android.os.SystemClock::elapsedRealtimeNanos,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val diagnostic: (String) -> Unit = {},
) {
    // Freeze the session description. Capabilities require both verified limits and a real reading.
    private val climateCapabilities = runCatching {
        val readings = climate?.snapshot()?.targetsCelsius.orEmpty()
        climate?.capabilities().orEmpty().filter { c ->
            c.minCelsius.isFinite() && c.maxCelsius.isFinite() && c.minCelsius < c.maxCelsius &&
                c.stepCelsius.isFinite() && c.stepCelsius > 0 &&
                readings[c.zone]?.let { validTemperature(c, it) } == true
        }.distinctBy { it.zone }
    }.getOrDefault(emptyList())
    private val radioProtocol = radio?.let { UltraRadioProtocol(it, radioElapsedNanos) }
    private val radioAdvertised = radioProtocol?.accessories()?.values?.isNotEmpty() == true
    private var radioRetired = false
    private class Write(val lane: Lane, val transaction: Value, val deadline: Long,
        val results: MutableMap<Long, Value>, val remaining: MutableSet<Long>) {
        val cancellations = mutableListOf<java.io.Closeable>()
    }
    private var pendingWrite: Write? = null
    private val completedWrites = mutableListOf<Message>()

    enum class Lane(val uuid: String) {
        HIGH("FF4A6720-F2BE-4F56-A3E1-DB3B4E37D634"),
        NORMAL("3E2F3C61-AAD0-42CB-A8AA-BF22186DA62E"),
    }

    data class Message(val lane: Lane, val bytes: ByteArray)
    sealed interface Poll {
        data class Messages(val messages: List<Message>) : Poll
        /** Invalidating wire semantics remain unverified; do not leave a stale live value on screen. */
        data object SourceLost : Poll
    }

    private val followed = mutableSetOf<Long>()
    private var lastValues = emptyMap<Long, Value>()
    var lastCommand: String? = null
        private set

    @Synchronized fun reset() {
        val retired = pendingWrite
        pendingWrite = null
        retired?.cancellations?.forEach { runCatching { it.close() } }
        completedWrites.clear()
        radioProtocol?.reset()
        followed.clear()
        lastValues = emptyMap()
        lastCommand = null
    }

    /** Reference /info payload, published only with the explicit experimental opt-in. */
    @Synchronized fun description(): Dict = dict(
        "protocolVersion" to Text("1.0"), "pluginCount" to Integer(1),
        "pluginMapping" to dict(PLUGIN to Integer(0)),
        "pluginConfigs" to Array(listOf(dict(
            "name" to Text(PLUGIN), "pluginID" to Integer(0), "accessories" to accessories(values()),
        ))),
    )

    /** Fixed capability metadata only; local SOC is not an advertised high-voltage battery service. */
    @Synchronized fun capabilityDiagnostics(): String {
        val described = accessories(values()).values.map { it as Dict }
        val range = described.any { it["type"] == Integer(0x04000001) }
        val zones = described.firstOrNull { it["type"] == Integer(0x01000001) }
            ?.let { (it["services"] as Array).values.size } ?: 0
        val radio = described.any { it["type"] == Integer(0x03000001) }
        val localSoc = runCatching { source.snapshot()?.batteryPercent?.let { it.isFinite() && it in 0.0..100.0 } == true }
            .getOrDefault(false)
        return "vehicle state capabilities range=$range climateZones=$zones radio=$radio " +
            "highVoltageBattery=false localSocAvailable=$localSoc"
    }

    fun infoPlist(): Map<String, Any?> {
        fun convert(value: Value): Any = when (value) {
            is Integer -> value.value
            is Text -> value.value
            is Bool -> value.value
            is Opack.Real -> value.value
            is Array -> value.values.map(::convert)
            is Dict -> value.entries.associate { (key, item) -> (key as Text).value to convert(item) }
            else -> error("Unsupported vehicle description value")
        }
        @Suppress("UNCHECKED_CAST")
        return convert(description()) as Map<String, Any?>
    }

    @Synchronized fun handle(lane: Lane, bytes: ByteArray): List<Message> {
        // Never log peer text, payload values, transaction IDs or raw packets.
        fun report(detail: String) { runCatching { diagnostic("vehicle state $detail lane=${lane.name}") } }
        fun reject(reason: String): List<Message> {
            report("rejected=$reason")
            return emptyList()
        }
        report("received bytes=${bytes.size}")
        val outer = Opack.decode(bytes) as? Dict ?: return reject("outer-dictionary")
        if (outer["pluginID"] != Integer(0)) return reject("plugin-id")
        val request = (outer["pluginData"] as? Data)?.bytes()?.let(Opack::decode) as? Dict
            ?: return reject("request-dictionary")
        val command = (request["command"] as? Text)?.value ?: return reject("command-type")
        val safeCommand = command.takeIf { it in setOf("configRequest", "registerRequest", "writeRequest",
            "controlRequest", "readRequest", "unregisterRequest") } ?: "unknown"
        report("request=$safeCommand")
        val transaction = request["transactionID"] ?: Opack.Null
        if (transaction !is Integer && transaction !is Opack.Uuid && transaction != Opack.Null) return reject("transaction-type")
        val current = values()
        if (current.isEmpty()) reset()
        val response = when (command) {
            "configRequest" -> dict(
                "command" to Text("configResponse"), "transactionID" to transaction,
                "values" to dict("accessories" to accessories(current)),
            )
            "registerRequest" -> {
                val requested = (request["values"] as? Array)?.values ?: return reject("register-values-type")
                if (requested.any { it !is Integer }) return reject("register-id-type")
                val held = mutableListOf<Pair<Value, Value>>()
                val missing = mutableListOf<Pair<Value, Value>>()
                requested.distinct().forEach { key ->
                    val iid = (key as Integer).value
                    if (iid in IIDS || (radioAdvertised && radioProtocol?.knowsReadIid(iid) == true) || climateCapabilities.any { targetIid(it.zone) == iid || targetIid(it.zone) + 2 == iid }) followed += iid
                    val value = current[iid]
                    if (value != null) {
                        held += key to value
                        lastValues = lastValues + (iid to value)
                    } else missing += key to Integer(NO_VALUE)
                }
                report("registration requested=${requested.distinct().size} available=${held.size} unavailable=${missing.size}")
                Dict(listOf(
                    Text("command") to Text("registerResponse"), Text("transactionID") to transaction,
                    Text("values") to Dict(held),
                ) + if (missing.isEmpty()) emptyList() else listOf(Text("errors") to Dict(missing)))
            }
            "writeRequest" -> {
                val requested = (request["values"] as? Dict)?.entries ?: return reject("write-values-type")
                if (requested.any { it.first !is Integer } || requested.map { it.first }.distinct().size != requested.size) return reject("write-ids")
                if (climateCapabilities.isNotEmpty()) {
                    lastCommand = command
                    return writeTemperatures(lane, transaction, requested)
                }
                report("write unavailable=${requested.size}")
                dict("command" to Text("writeResponse"), "transactionID" to transaction,
                    "errors" to Dict(requested.map { it.first to Integer(NO_VALUE) }))
            }
            "controlRequest" -> {
                val requested = (request["values"] as? Dict)?.entries ?: return reject("control-values-type")
                if (requested.any { it.first !is Integer }) return reject("control-id-type")
                if (radioAdvertised && !radioRetired && current.isNotEmpty()) {
                    lastCommand = command
                    return radioProtocol!!.handle(lane, bytes).map { Message(it.lane, it.bytes) }
                }
                report("control unavailable=${requested.size}")
                dict("command" to Text("controlResponse"), "transactionID" to transaction,
                    "errors" to Dict(requested.map { it.first to Integer(NO_COMMAND) }))
            }
            else -> return reject("unsupported-command")
        }
        lastCommand = command
        return listOf(Message(lane, wrap(response)))
    }

    /** The provider owns freshness. A null/invalid snapshot clears every subscription. */
    @Synchronized fun poll(): Poll {
        val current = values()
        if (current.isEmpty()) {
            reset()
            return Poll.SourceLost
        }
        if (followed.any { it in lastValues && it !in current }) {
            if (radioAdvertised) retireRadio()
            reset()
            return Poll.SourceLost
        }
        val radioReplies = if (radioAdvertised) when (val update = radioProtocol!!.poll()) {
            UltraRadioProtocol.Poll.SourceLost -> { retireRadio(); reset(); return Poll.SourceLost }
            is UltraRadioProtocol.Poll.Messages -> update.messages.map { Message(it.lane, it.bytes) }
        } else emptyList()
        pendingWrite?.takeIf { elapsedMillis() >= it.deadline }?.let { write ->
            write.remaining.toList().forEach { finishTemperature(write, it, false) }
        }
        val replies = completedWrites.toList()
        completedWrites.clear()
        val changed = followed.mapNotNull { iid ->
            current[iid]?.takeIf { it != lastValues[iid] }?.let { Integer(iid) to it }
        }
        lastValues = current
        return Poll.Messages(replies + radioReplies + if (changed.isEmpty()) emptyList() else listOf(Message(Lane.HIGH, wrap(dict(
            "command" to Text("updateNotify"), "values" to Dict(changed),
        )))))
    }

    private fun values(): Map<Long, Value> {
        val values = mutableMapOf<Long, Value>()
        source.snapshot()?.takeIf { it.rangeKm in 0..65535 }?.let { snapshot ->
            values[RANGE_KM] = Integer(snapshot.rangeKm.toLong())
            values[RANGE_MILES] = Integer((snapshot.rangeKm / 1.609344).roundToLong())
        }
        if (climateCapabilities.isNotEmpty()) {
            val readings = runCatching { climate?.snapshot()?.targetsCelsius }.getOrNull() ?: return emptyMap()
            for (capability in climateCapabilities) {
                val value = readings[capability.zone] ?: return emptyMap()
                if (!validTemperature(capability, value)) return emptyMap()
                values[targetIid(capability.zone)] = Opack.Real(value)
                values[targetIid(capability.zone) + 2] = Text(layoutKey(capability.seat))
            }
        }
        if (radioAdvertised) {
            if (radioRetired) return emptyMap()
            val radioValues = radioProtocol!!.observedValues()
            if (radioValues == null) { retireRadio(); return emptyMap() }
            values.putAll(radioValues)
        }
        return values
    }

    private fun retireRadio() { radioRetired = true; radioProtocol?.close() }

    @Synchronized fun close() { reset(); radioProtocol?.close() }

    @Synchronized fun isSourceAvailable(): Boolean {
        val current = values()
        if (current.isEmpty()) return false
        if (followed.any { it in lastValues && it !in current }) {
            if (radioAdvertised) retireRadio()
            return false
        }
        return true
    }

    private fun accessories(current: Map<Long, Value>): Array = Array(
        (if (RANGE_KM in current && RANGE_MILES in current) listOf(dict(
        "iid" to Integer(1792), "type" to Integer(0x04000001), "version" to Text("1.0"),
        "services" to Array(listOf(dict(
            "iid" to Integer(1793), "type" to Integer(0x1a00000b),
            "characteristics" to Array(listOf(
                characteristic(RANGE_KM, 0x3000004a, 151, current[RANGE_KM]),
                characteristic(RANGE_MILES, 0x3000004b, 164, current[RANGE_MILES]),
            )),
        ))),
    )) else emptyList()) +
        (if (climateCapabilities.isEmpty()) emptyList() else listOf(climateAccessory(current))) +
        (if (!radioAdvertised || radioRetired || current.isEmpty()) emptyList() else radioProtocol!!.accessories().values))

    private fun climateAccessory(current: Map<Long, Value>): Dict = dict(
        "iid" to Integer(512), "type" to Integer(0x01000001), "version" to Text("1.0"),
        "services" to Array(climateCapabilities.map { c ->
            val iid = targetIid(c.zone)
            dict("iid" to Integer(iid - 2), "type" to Integer(0x11000002),
                "characteristics" to Array(listOf(Dict(dict(
                    "iid" to Integer(iid), "type" to Integer(0x31000017), "format" to Integer(9),
                    "mutable" to Bool(true), "writable" to Bool(true), "oemWritable" to Bool(true),
                    "priority" to Integer(1), "units" to Integer(1082),
                    "minimumValue" to Opack.Real(c.minCelsius), "maximumValue" to Opack.Real(c.maxCelsius),
                    "stepValue" to Opack.Real(c.stepCelsius),
                ).entries + current[iid]?.let { listOf(Text("initialValue") to it) }.orEmpty()),
                dict("iid" to Integer(iid + 2), "type" to Integer(0x36000065),
                    "format" to Integer(10), "mutable" to Bool(false), "writable" to Bool(false),
                    "oemWritable" to Bool(true), "priority" to Integer(1), "maximumLength" to Integer(65535),
                    "initialValue" to Text(layoutKey(c.seat))))))
        }),
    )

    private fun writeTemperatures(lane: Lane, transaction: Value, entries: List<Pair<Value, Value>>): List<Message> {
        val results = entries.associate { (key, _) -> (key as Integer).value to Integer(NO_VALUE) as Value }.toMutableMap()
        val accepted = entries.mapNotNull { (key, value) ->
            val iid = (key as Integer).value
            val c = climateCapabilities.firstOrNull { targetIid(it.zone) == iid } ?: return@mapNotNull null
            val number = when (value) { is Opack.Real -> value.value; is Integer -> value.value.toDouble(); else -> return@mapNotNull null }
            if (!validTemperature(c, number)) null else Triple(iid, c.zone, number)
        }
        if (pendingWrite != null || completedWrites.size >= 16 || accepted.isEmpty() || !isSourceAvailable())
            return listOf(writeResponse(lane, transaction, results))
        val backend = climate ?: return listOf(writeResponse(lane, transaction, results))
        val write = Write(lane, transaction, elapsedMillis() + 10_000, results, accepted.map { it.first }.toMutableSet())
        pendingWrite = write
        accepted.forEach { (iid, zone, value) ->
            try {
                val cancellation = backend.requestTemperature(zone, value) { confirmed ->
                    synchronized(this) {
                        val actual = runCatching { backend.snapshot()?.targetsCelsius?.get(zone) }.getOrNull()
                        finishTemperature(write, iid, confirmed && actual != null && kotlin.math.abs(actual - value) < 0.000001)
                    }
                }
                if (pendingWrite === write) write.cancellations += cancellation else runCatching { cancellation.close() }
            } catch (_: Exception) { finishTemperature(write, iid, false) }
        }
        return emptyList()
    }

    private fun finishTemperature(write: Write, iid: Long, confirmed: Boolean) {
        if (pendingWrite !== write || !write.remaining.remove(iid)) return
        write.results[iid] = Integer(if (confirmed) 0 else NO_VALUE)
        if (write.remaining.isEmpty()) {
            pendingWrite = null
            completedWrites += writeResponse(write.lane, write.transaction, write.results)
            write.cancellations.forEach { runCatching { it.close() } }
        }
    }

    private fun writeResponse(lane: Lane, transaction: Value, results: Map<Long, Value>) = Message(lane, wrap(dict(
        "command" to Text("writeResponse"), "transactionID" to transaction,
        "errors" to Dict(results.map { Integer(it.key) to it.value }),
    )))

    private fun layoutKey(seat: UltraClimateBackend.Seat) = when (seat) {
        UltraClimateBackend.Seat.FRONT_LEFT -> "seat_front_left"
        UltraClimateBackend.Seat.FRONT_RIGHT -> "seat_front_right"
    }
    private fun targetIid(zone: UltraClimateBackend.Zone): Long = if (zone == UltraClimateBackend.Zone.DRIVER) 520 else 534
    private fun validTemperature(c: UltraClimateBackend.Capability, value: Double): Boolean = value.isFinite() &&
        value >= c.minCelsius && value <= c.maxCelsius &&
        kotlin.math.abs((value - c.minCelsius) / c.stepCelsius - kotlin.math.round((value - c.minCelsius) / c.stepCelsius)) < 0.000001

    private fun characteristic(iid: Long, type: Long, units: Long, initial: Value?): Dict = Dict(
        dict(
            "iid" to Integer(iid), "type" to Integer(type), "format" to Integer(4),
            "mutable" to Bool(true), "writable" to Bool(false), "oemWritable" to Bool(true),
            "priority" to Integer(1), "minimumValue" to Integer(0),
            "maximumValue" to Integer(0xffff_ffffL), "stepValue" to Integer(1), "units" to Integer(units),
        ).entries + if (initial == null) emptyList() else listOf(Text("initialValue") to initial),
    )

    private fun wrap(inner: Dict): ByteArray = Opack.encode(dict(
        "pluginID" to Integer(0), "pluginData" to Data(Opack.encode(inner)),
    ))

    companion object {
        const val RANGE_KM = 1794L
        const val RANGE_MILES = 1795L
        private val IIDS = setOf(RANGE_KM, RANGE_MILES)
        private const val PLUGIN = "DiPlayC11Prototype"
        private const val NO_VALUE = -6727L
        private const val NO_COMMAND = -6735L
    }
}
