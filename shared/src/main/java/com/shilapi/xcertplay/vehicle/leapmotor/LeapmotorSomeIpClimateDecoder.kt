package com.shilapi.xcertplay.vehicle.leapmotor

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Read-only C11 TMS target-temperature events, verified in the OEM AirManager consumer.
 * SomeIpService subscribes service 0x9900 / instance 0x9901 / group 0x9903. AirManager registers
 * 11205/11206 as SubEvent valueType=2; SomeIpSubManageImpl requires exactly eight bytes and
 * ByteUtils decodes an IEEE754 little-endian double. onMessageDouble accepts 18..32 Celsius,
 * converting to doubled UI units only after reception. HPC request IDs 7610/7611 are not state.
 *
 * No write route, power-state inference, driver-side assumption or temperature-step capability.
 * A decoded observation is not a setter confirmation and must not enable Ultra climate controls.
 */
object LeapmotorSomeIpClimateDecoder {
    const val SERVICE = 0x9900
    const val INSTANCE = 0x9901
    const val GROUP = 0x9903
    const val LEFT_TARGET_EVENT = 11205
    const val RIGHT_TARGET_EVENT = 11206

    data class Target(val side: LeapmotorClimateSide, val celsius: Double)

    fun decode(service: Int, instance: Int, event: Int, payload: ByteArray): Target? {
        if (service != SERVICE || instance != INSTANCE || payload.size != 8) return null
        val side = when (event) {
            LEFT_TARGET_EVENT -> LeapmotorClimateSide.LEFT
            RIGHT_TARGET_EVENT -> LeapmotorClimateSide.RIGHT
            else -> return null
        }
        val value = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).double
        if (!value.isFinite() || value !in 18.0..32.0) return null
        return Target(side, value)
    }
}
