package com.shilapi.xcertplay.vehicle.leapmotor

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Passive direct-TPMS contract from OEM LeapCenterManager.onMessageInteger. Values remain RAW:
 * Java forwards them unchanged to Unity, and physical units/conversion are not yet verified.
 * No subscription, health/alarm inference, vehicle capability advertisement or write route.
 */
object LeapmotorSomeIpTireDecoder {
    const val SERVICE = 0x9900
    const val INSTANCE = 0x9901
    const val GROUP = 0x9903

    enum class Wheel { FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT }
    enum class Quantity { RAW_PRESSURE, RAW_TEMPERATURE }
    data class Channel(val wheel: Wheel, val quantity: Quantity)
    data class Observation(val channel: Channel, val rawValue: Int?)

    fun channel(event: Int): Channel? = when (event) {
        3403 -> Channel(Wheel.FRONT_LEFT, Quantity.RAW_PRESSURE)
        3503 -> Channel(Wheel.FRONT_RIGHT, Quantity.RAW_PRESSURE)
        3603 -> Channel(Wheel.REAR_LEFT, Quantity.RAW_PRESSURE)
        3703 -> Channel(Wheel.REAR_RIGHT, Quantity.RAW_PRESSURE)
        3404 -> Channel(Wheel.FRONT_LEFT, Quantity.RAW_TEMPERATURE)
        3504 -> Channel(Wheel.FRONT_RIGHT, Quantity.RAW_TEMPERATURE)
        3604 -> Channel(Wheel.REAR_LEFT, Quantity.RAW_TEMPERATURE)
        3704 -> Channel(Wheel.REAR_RIGHT, Quantity.RAW_TEMPERATURE)
        else -> null
    }

    /** Null means unrelated identity/event; an observation with null value withdraws that field. */
    fun decode(service: Int, instance: Int, group: Int, event: Int,
        payload: ByteArray?): Observation? {
        if (service != SERVICE || instance != INSTANCE || group != GROUP) return null
        val channel = channel(event) ?: return null
        val raw = payload?.takeIf { it.size == 4 }?.let {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int
        }
        val valid = raw != null && when (channel.quantity) {
            Quantity.RAW_PRESSURE -> raw in 0..4094
            Quantity.RAW_TEMPERATURE -> raw in -50..150
        }
        // -1 is a valid raw temperature. Never apply the bean's pressure sentinel to it.
        return Observation(channel, raw.takeIf { valid })
    }
}
