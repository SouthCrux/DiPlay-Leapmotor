package com.shilapi.xcertplay.vehicle.leapmotor

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** OEM TPMS flags, kept separate from physical readings and Android wheel area IDs. */
object LeapmotorSomeIpTireFlagsDecoder {
    enum class Kind { LEAK, PRESSURE, TEMPERATURE }
    data class Channel(val wheel: LeapmotorSomeIpTireDecoder.Wheel, val kind: Kind)
    data class Observation(val channel: Channel, val rawValue: Int?)

    fun channel(event: Int): Channel? {
        val wheel = when (event) {
            in 3301..3303 -> LeapmotorSomeIpTireDecoder.Wheel.FRONT_LEFT
            in 3306..3308 -> LeapmotorSomeIpTireDecoder.Wheel.FRONT_RIGHT
            in 3311..3313 -> LeapmotorSomeIpTireDecoder.Wheel.REAR_LEFT
            in 3316..3318 -> LeapmotorSomeIpTireDecoder.Wheel.REAR_RIGHT
            else -> return null
        }
        val kind = when ((event - 3301) % 5) {
            0 -> Kind.LEAK
            1 -> Kind.PRESSURE
            else -> Kind.TEMPERATURE
        }
        return Channel(wheel, kind)
    }

    /** No guessed flag enum: signed integer RAW is retained, including unknown nonzero codes. */
    fun decode(service: Int, instance: Int, group: Int, event: Int,
        payload: ByteArray?): Observation? {
        if (service != LeapmotorSomeIpTireDecoder.SERVICE ||
            instance != LeapmotorSomeIpTireDecoder.INSTANCE ||
            group != LeapmotorSomeIpTireDecoder.GROUP) return null
        val channel = channel(event) ?: return null
        val raw = payload?.takeIf { it.size == 4 }?.let {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int
        }
        return Observation(channel, raw)
    }
}

/**
 * Passive, independently expiring flag cache. ZERO_FLAGS_OBSERVED reproduces only TPMSBean's
 * zero check, not proof of sensor health, valid pressure, calibration or physical measurement.
 * No defaults count as observations. No transport, control, polling or Apple advertisement.
 */
class LeapmotorSomeIpTireFlagsState(
    private val elapsedMillis: () -> Long,
    private val silenceMillis: Long = 60_000L,
) {
    enum class Check { UNKNOWN, ZERO_FLAGS_OBSERVED, NONZERO_FLAG_OBSERVED }
    data class Flag(val channel: LeapmotorSomeIpTireFlagsDecoder.Channel,
        val rawValue: Int?, val receivedAgeMillis: Long?)
    data class Reading(val wheel: LeapmotorSomeIpTireDecoder.Wheel,
        val pressureCheck: Check, val temperatureCheck: Check, val flags: List<Flag>)
    private data class Field(var raw: Int? = null, var at: Long? = null)
    private val fields = LeapmotorSomeIpTireDecoder.Wheel.entries.flatMap { wheel ->
        LeapmotorSomeIpTireFlagsDecoder.Kind.entries.map { kind ->
            LeapmotorSomeIpTireFlagsDecoder.Channel(wheel, kind) to Field()
        }
    }.toMap()
    private var epoch = 0L
    private var active = false

    init { require(silenceMillis > 0) }

    @Synchronized fun beginSource(): Long { clear(); active = true; return epoch }
    @Synchronized fun clear() {
        epoch++
        active = false
        fields.values.forEach { it.raw = null; it.at = null }
    }

    @Synchronized fun receive(sourceEpoch: Long, service: Int, instance: Int, group: Int,
        event: Int, payload: ByteArray?, locallyReceivedAtMillis: Long): Boolean {
        if (!active || sourceEpoch != epoch) return false
        val observation = LeapmotorSomeIpTireFlagsDecoder.decode(service, instance, group,
            event, payload) ?: return false
        val field = fields.getValue(observation.channel)
        val now = elapsedMillis()
        if (locallyReceivedAtMillis < 0 || locallyReceivedAtMillis > now ||
            now - locallyReceivedAtMillis > silenceMillis ||
            field.at?.let { locallyReceivedAtMillis < it } == true) {
            field.raw = null
            return true
        }
        field.at = locallyReceivedAtMillis
        field.raw = observation.rawValue
        return true
    }

    @Synchronized fun readings(): List<Reading> {
        val now = elapsedMillis()
        val flags = fields.map { (channel, field) ->
            val age = field.at?.takeIf { it <= now }?.let { now - it }
            if (age == null || age > silenceMillis) field.raw = null
            Flag(channel, field.raw, age)
        }
        return LeapmotorSomeIpTireDecoder.Wheel.entries.map { wheel ->
            val wheelFlags = flags.filter { it.channel.wheel == wheel }
            fun value(kind: LeapmotorSomeIpTireFlagsDecoder.Kind) =
                wheelFlags.single { it.channel.kind == kind }.rawValue
            // Missing constituents win over an observed nonzero flag: report raw independently.
            fun check(vararg values: Int?): Check = when {
                values.any { it == null } -> Check.UNKNOWN
                values.all { it == 0 } -> Check.ZERO_FLAGS_OBSERVED
                else -> Check.NONZERO_FLAG_OBSERVED
            }
            Reading(wheel,
                check(value(LeapmotorSomeIpTireFlagsDecoder.Kind.LEAK),
                    value(LeapmotorSomeIpTireFlagsDecoder.Kind.PRESSURE)),
                check(value(LeapmotorSomeIpTireFlagsDecoder.Kind.TEMPERATURE)), wheelFlags)
        }
    }
}
