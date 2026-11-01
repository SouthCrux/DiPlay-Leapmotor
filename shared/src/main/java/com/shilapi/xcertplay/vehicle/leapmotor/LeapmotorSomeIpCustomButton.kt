package com.shilapi.xcertplay.vehicle.leapmotor

/**
 * Read-only interpretation of the OEM ExtendControlManager byte callback.
 * These values set wheelClick in the inspected firmware; observing them does not suppress
 * the OEM action or establish that a notification is a fresh physical press.
 */
object LeapmotorSomeIpCustomButton {
    const val SERVICE = 4161
    const val INSTANCE = 20545
    const val GROUP = 17920
    const val EVENT = 4115

    enum class Click { SINGLE, DOUBLE }

    fun accepts(service: Int, instance: Int, group: Int, event: Int): Boolean =
        service == SERVICE && instance == INSTANCE && group == GROUP && event == EVENT

    fun decode(service: Int, instance: Int, group: Int, event: Int, payload: ByteArray?): Click? {
        if (!accepts(service, instance, group, event) || payload?.size != 1) return null
        return when (payload[0].toInt()) {
            49 -> Click.SINGLE // ASCII '1'
            51 -> Click.DOUBLE // ASCII '3', not numeric 3 or ASCII '2'
            else -> null
        }
    }
}
