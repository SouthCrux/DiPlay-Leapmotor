package com.shilapi.xcertplay.vehicle.leapmotor

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** AirManager subscribes 1221/1016 with valueType2 (double LE8). Neither is an HVAC target.
 * Units, physical limits and finite OEM sentinels are UNVERIFIED: diagnostic observations only.
 */
object LeapmotorSomeIpAmbientDecoder {
    const val SERVICE = 0x9900
    const val INSTANCE = 0x9901
    const val GROUP = 0x9903
    const val INTERIOR_EVENT = 1221
    const val EXTERIOR_CORRECTED_EVENT = 1016
    enum class Channel { INTERIOR, EXTERIOR_CORRECTED }
    data class Observation(val channel: Channel, val rawValue: Double?)

    fun channel(event: Int): Channel? = when (event) {
        INTERIOR_EVENT -> Channel.INTERIOR
        EXTERIOR_CORRECTED_EVENT -> Channel.EXTERIOR_CORRECTED
        else -> null
    }

    fun decode(service: Int, instance: Int, group: Int, event: Int,
        payload: ByteArray?): Observation? {
        if (service != SERVICE || instance != INSTANCE || group != GROUP) return null
        val channel = channel(event) ?: return null
        val raw = payload?.takeIf { it.size == 8 }?.let {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).double
        }?.takeIf { it.isFinite() }
        return Observation(channel, raw)
    }
}
