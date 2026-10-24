package com.shilapi.xcertplay.vehicle.leapmotor

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Physical roof curtain only. Event 2800 belongs to the separate glass/skylight contract. */
object LeapmotorSomeIpSunshadeDecoder {
    const val SERVICE = 0x9900
    const val INSTANCE = 0x9901
    const val GROUP = 0x9903
    const val POSITION_EVENT = 2801
    data class Position(val rawPosition: Int, val oemUiPosition: Int)

    /** OEM BaseControlManager accepts 0..100, then sends 100-raw to Unity status 327006. */
    fun decode(service: Int, instance: Int, group: Int, event: Int, payload: ByteArray?): Position? {
        if (service != SERVICE || instance != INSTANCE || group != GROUP || event != POSITION_EVENT ||
            payload?.size != 4) return null
        val raw = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int
        return raw.takeIf { it in 0..100 }?.let { Position(it, 100 - it) }
    }
}

/** Passive, transport-owned cache. Neither representation is labelled open/closed without a car check. */
class LeapmotorSomeIpSunshadeState(
    private val elapsedMillis: () -> Long,
    private val silenceMillis: Long = 60_000L,
) {
    enum class Status { UNAVAILABLE, AVAILABLE, STALE }
    data class Reading(val status: Status, val rawPosition: Int?, val oemUiPosition: Int?,
        val receivedAgeMillis: Long?)
    private var epoch = 0L
    private var active = false
    private var position: LeapmotorSomeIpSunshadeDecoder.Position? = null
    private var at: Long? = null
    private var status = Status.UNAVAILABLE
    init { require(silenceMillis > 0) }

    @Synchronized fun beginSource(): Long { clear(); active = true; return epoch }
    @Synchronized fun clear() {
        epoch++; active = false; position = null; at = null; status = Status.UNAVAILABLE
    }
    @Synchronized fun receive(sourceEpoch: Long, service: Int, instance: Int, group: Int,
        event: Int, payload: ByteArray?, receivedAt: Long): Boolean {
        if (!active || sourceEpoch != epoch || service != LeapmotorSomeIpSunshadeDecoder.SERVICE ||
            instance != LeapmotorSomeIpSunshadeDecoder.INSTANCE || group != LeapmotorSomeIpSunshadeDecoder.GROUP ||
            event != LeapmotorSomeIpSunshadeDecoder.POSITION_EVENT) return false
        val now = elapsedMillis()
        val previous = at
        if (receivedAt < 0 || receivedAt > now || now - receivedAt > silenceMillis ||
            previous != null && receivedAt < previous) {
            position = null; status = Status.UNAVAILABLE
            return true
        }
        at = receivedAt
        position = LeapmotorSomeIpSunshadeDecoder.decode(service, instance, group, event, payload)
        status = if (position == null) Status.UNAVAILABLE else Status.AVAILABLE
        return true
    }
    @Synchronized fun reading(): Reading {
        val now = elapsedMillis()
        val age = at?.takeIf { it <= now }?.let { now - it }
        if (position != null && (age == null || age > silenceMillis)) {
            position = null; status = Status.STALE
        }
        return Reading(status, position?.rawPosition, position?.oemUiPosition, age)
    }
}
