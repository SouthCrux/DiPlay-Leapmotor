package com.shilapi.xcertplay.vehicle.leapmotor

/**
 * Unbound raw TPMS cache. The 60s silence limit is a local withdrawal policy, not an OEM cadence
 * or measurement timestamp. Each wheel/quantity expires independently. A transport owner must
 * explicitly begin/clear its source; this class does not open a vehicle connection.
 */
class LeapmotorSomeIpTireState(
    private val elapsedMillis: () -> Long,
    private val silenceMillis: Long = 60_000L,
) {
    enum class Status { UNAVAILABLE, AVAILABLE, STALE }
    data class Reading(val channel: LeapmotorSomeIpTireDecoder.Channel, val status: Status,
        val rawValue: Int?, val receivedAgeMillis: Long?)
    private data class Field(var raw: Int? = null, var at: Long? = null,
        var status: Status = Status.UNAVAILABLE)
    private val fields = LeapmotorSomeIpTireDecoder.Wheel.entries.flatMap { wheel ->
        LeapmotorSomeIpTireDecoder.Quantity.entries.map { quantity ->
            LeapmotorSomeIpTireDecoder.Channel(wheel, quantity) to Field()
        }
    }.toMap()
    private var epoch = 0L
    private var active = false
    init { require(silenceMillis > 0) }

    @Synchronized fun beginSource(): Long { clear(); active = true; return epoch }
    @Synchronized fun clear() {
        epoch++
        active = false
        fields.values.forEach { it.raw = null; it.at = null; it.status = Status.UNAVAILABLE }
    }

    @Synchronized fun receive(sourceEpoch: Long, service: Int, instance: Int, group: Int,
        event: Int, payload: ByteArray?, locallyReceivedAtMillis: Long): Boolean {
        if (!active || sourceEpoch != epoch) return false
        val observation = LeapmotorSomeIpTireDecoder.decode(service, instance, group, event, payload)
            ?: return false
        val field = fields.getValue(observation.channel)
        val now = elapsedMillis()
        val previous = field.at
        if (locallyReceivedAtMillis < 0 || locallyReceivedAtMillis > now ||
            now - locallyReceivedAtMillis > silenceMillis ||
            previous != null && locallyReceivedAtMillis < previous) {
            field.raw = null
            field.status = Status.UNAVAILABLE
            return true
        }
        field.at = locallyReceivedAtMillis
        field.raw = observation.rawValue
        field.status = if (field.raw == null) Status.UNAVAILABLE else Status.AVAILABLE
        return true
    }

    @Synchronized fun readings(): List<Reading> {
        val now = elapsedMillis()
        return fields.map { (channel, field) ->
            val age = field.at?.takeIf { it <= now }?.let { now - it }
            if (field.raw != null && (age == null || age > silenceMillis)) {
                field.raw = null
                field.status = Status.STALE
            }
            Reading(channel, field.status, field.raw, age)
        }
    }
}
