package com.shilapi.xcertplay.vehicle.leapmotor

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Passive front HVAC fan level. OEM AirManager registers event 1008 as valueType 1 (int32LE)
 * and accepts only 0..7 in onMessageInteger before updating mWindNum / Unity 101009.
 * This is a level, not RPM, percent, compressor state, or proof that HVAC is powered on.
 * It adds no subscription, write route, or iPhone capability by itself.
 */
object LeapmotorSomeIpFanDecoder {
    const val SERVICE = 0x9900
    const val INSTANCE = 0x9901
    const val GROUP = 0x9903
    const val LEVEL_EVENT = 1008
    data class Level(val level: Int)

    /** Route identity and exact integer wire shape are checked before the OEM range. */
    fun decode(service: Int, instance: Int, group: Int, event: Int, payload: ByteArray?): Level? {
        if (service != SERVICE || instance != INSTANCE || group != GROUP || event != LEVEL_EVENT ||
            payload?.size != 4) return null
        val raw = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int
        return raw.takeIf { it in 0..7 }?.let { Level(it) }
    }
}

/** Transport-owned receipt cache; silence is unknown, never an inferred zero/off state. */
class LeapmotorSomeIpFanState(
    private val elapsedMillis: () -> Long,
    private val silenceMillis: Long = 60_000L,
) {
    enum class Status { UNAVAILABLE, AVAILABLE, STALE }
    data class Reading(val status: Status, val level: Int?,
        val receivedAgeMillis: Long?)
    private var epoch = 0L
    private var active = false
    private var observed: LeapmotorSomeIpFanDecoder.Level? = null
    private var at: Long? = null
    private var status = Status.UNAVAILABLE
    init { require(silenceMillis > 0) }

    @Synchronized fun beginSource(): Long { clear(); active = true; return epoch }
    @Synchronized fun clear() {
        epoch++; active = false; observed = null; at = null; status = Status.UNAVAILABLE
    }
    @Synchronized fun receive(sourceEpoch: Long, service: Int, instance: Int, group: Int,
        event: Int, payload: ByteArray?, receivedAt: Long): Boolean {
        if (!active || sourceEpoch != epoch || service != LeapmotorSomeIpFanDecoder.SERVICE ||
            instance != LeapmotorSomeIpFanDecoder.INSTANCE || group != LeapmotorSomeIpFanDecoder.GROUP ||
            event != LeapmotorSomeIpFanDecoder.LEVEL_EVENT) return false
        val now = elapsedMillis()
        val previous = at
        if (receivedAt < 0 || receivedAt > now || now - receivedAt > silenceMillis ||
            previous != null && receivedAt < previous) {
            observed = null; status = Status.UNAVAILABLE
            return true
        }
        at = receivedAt
        observed = LeapmotorSomeIpFanDecoder.decode(service, instance, group, event, payload)
        status = if (observed == null) Status.UNAVAILABLE else Status.AVAILABLE
        return true
    }
    @Synchronized fun reading(): Reading {
        val now = elapsedMillis()
        val age = at?.takeIf { it <= now }?.let { now - it }
        if (observed != null && (age == null || age > silenceMillis)) {
            observed = null; status = Status.STALE
        }
        return Reading(status, observed?.level, age)
    }
}
