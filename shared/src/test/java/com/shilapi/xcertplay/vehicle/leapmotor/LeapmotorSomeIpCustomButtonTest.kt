package com.shilapi.xcertplay.vehicle.leapmotor

import org.junit.Assert.*
import org.junit.Test

class LeapmotorSomeIpCustomButtonTest {
    private fun decode(payload: ByteArray?) = LeapmotorSomeIpCustomButton.decode(4161, 20545, 17920, 4115, payload)

    @Test fun distinguishesOemAsciiValuesAndRejectsOtherByteValues() {
        for (value in 0..255) {
            val expected = when (value) {
                49 -> LeapmotorSomeIpCustomButton.Click.SINGLE
                51 -> LeapmotorSomeIpCustomButton.Click.DOUBLE
                else -> null
            }
            assertEquals("byte=$value", expected, decode(byteArrayOf(value.toByte())))
        }
        assertNull(decode(null))
        assertNull(decode(byteArrayOf()))
        assertNull(decode("1\u0000".toByteArray()))
        assertNull(decode("13".toByteArray()))
    }

    @Test fun rejectsVoiceButtonAndForeignRoutes() {
        val route = intArrayOf(4161, 20545, 17920, 4115)
        for (index in route.indices) {
            val foreign = route.copyOf().apply { this[index]-- }
            assertNull(LeapmotorSomeIpCustomButton.decode(
                foreign[0], foreign[1], foreign[2], foreign[3], byteArrayOf(49)))
        }
    }
}
