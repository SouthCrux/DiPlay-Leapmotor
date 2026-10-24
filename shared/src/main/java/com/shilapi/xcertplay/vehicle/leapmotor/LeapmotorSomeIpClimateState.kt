package com.shilapi.xcertplay.vehicle.leapmotor

/**
 * Passive target-temperature cache, separate from writable climate capabilities. Each physical side
 * expires independently after 60s of silence. OEM origin timestamps and update cadence are unknown;
 * age is time since receipt only. Invalid current-source events withdraw that side, while callbacks
 * from a retired source are ignored. A newer callback cannot revive another side's silent value.
 */
class LeapmotorSomeIpClimateState(
    private val elapsedMillis: () -> Long,
    private val silenceMillis: Long = 60_000L,
) {
    enum class Status { UNAVAILABLE, AVAILABLE, STALE }
    data class Reading(val side: LeapmotorClimateSide, val status: Status,
        val celsius: Double?, val receivedAgeMillis: Long?)
    private data class Field(var value: Double? = null, var at: Long? = null,
        var status: Status = Status.UNAVAILABLE)
    private val fields = LeapmotorClimateSide.entries.associateWith { Field() }
    private var epoch = 0L
    private var active = false
    init { require(silenceMillis > 0) }

    @Synchronized fun beginSource(): Long { clear(); active = true; return epoch }
    @Synchronized fun clear() {
        epoch++
        active = false
        fields.values.forEach { it.value = null; it.at = null; it.status = Status.UNAVAILABLE }
    }

    /** True only for an event belonging to the current source and this verified temperature pair. */
    @Synchronized fun receive(sourceEpoch: Long, service: Int, instance: Int, event: Int,
        payload: ByteArray?, receivedAt: Long): Boolean {
        if (!active || sourceEpoch != epoch || service != LeapmotorSomeIpClimateDecoder.SERVICE ||
            instance != LeapmotorSomeIpClimateDecoder.INSTANCE) return false
        val side = when (event) {
            LeapmotorSomeIpClimateDecoder.LEFT_TARGET_EVENT -> LeapmotorClimateSide.LEFT
            LeapmotorSomeIpClimateDecoder.RIGHT_TARGET_EVENT -> LeapmotorClimateSide.RIGHT
            else -> return false
        }
        val field = fields.getValue(side)
        val now = elapsedMillis()
        val previous = field.at
        if (receivedAt < 0 || receivedAt > now || now - receivedAt > silenceMillis ||
            previous != null && receivedAt < previous) {
            field.value = null
            field.status = Status.UNAVAILABLE
            return true
        }
        field.at = receivedAt
        field.value = payload?.let {
            LeapmotorSomeIpClimateDecoder.decode(service, instance, event, it)?.celsius
        }
        field.status = if (field.value == null) Status.UNAVAILABLE else Status.AVAILABLE
        return true
    }

    @Synchronized fun readings(): List<Reading> {
        val now = elapsedMillis()
        return fields.map { (side, field) ->
            val at = field.at
            val age = at?.takeIf { it <= now }?.let { now - it }
            val stale = field.value != null && (age == null || age > silenceMillis)
            // Withdraw expired data permanently, including if a clock is subsequently reset.
            if (stale) { field.value = null; field.status = Status.STALE }
            Reading(side, field.status, field.value, age)
        }
    }
}
