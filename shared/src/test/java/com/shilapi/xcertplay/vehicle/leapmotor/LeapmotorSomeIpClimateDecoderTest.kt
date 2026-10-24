package com.shilapi.xcertplay.vehicle.leapmotor

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LeapmotorSomeIpClimateDecoderTest {
    private fun wire(value: Double) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array()
    private fun decode(event: Int, payload: ByteArray) =
        LeapmotorSomeIpClimateDecoder.decode(0x9900, 0x9901, event, payload)

    @Test fun physicalSidesAndCelsiusAreDecodedWithoutApplyingOemUiDoubling() {
        // IEEE754 22.5 LE fixture; this must stay 22.5, not 45 UI half-degree units.
        val left = decode(11205, byteArrayOf(0, 0, 0, 0, 0, 0x80.toByte(), 0x36, 0x40))!!
        assertEquals(LeapmotorClimateSide.LEFT, left.side)
        assertEquals(22.5, left.celsius, 0.0)
        val right = decode(11206, wire(25.0))!!
        assertEquals(LeapmotorClimateSide.RIGHT, right.side)
        assertEquals(25.0, right.celsius, 0.0)
    }

    @Test fun oemConsumerRangeIsEnforcedWithoutInventingTemperatureIncrements() {
        assertEquals(18.0, decode(11205, wire(18.0))!!.celsius, 0.0)
        assertEquals(32.0, decode(11205, wire(32.0))!!.celsius, 0.0)
        assertEquals(22.25, decode(11205, wire(22.25))!!.celsius, 0.0)
        for (value in listOf(17.999, 32.001, Double.NaN, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, 0.0, -1.0)) assertNull(decode(11205, wire(value)))
    }

    @Test fun truncatedExtendedWrongEndianAndRequestEventsCannotBecomeState() {
        for (length in listOf(0, 1, 4, 7, 9, 16)) assertNull(decode(11205, ByteArray(length)))
        assertNull(decode(11205, wire(22.5).reversedArray()))
        for (event in listOf(7610, 7611, 1001, 11204, 11207)) assertNull(decode(event, wire(22.5)))
    }

    @Test fun exactServiceAndInstanceAreRequired() {
        val payload = wire(22.5)
        assertNull(LeapmotorSomeIpClimateDecoder.decode(0x9901, 0x9901, 11205, payload))
        assertNull(LeapmotorSomeIpClimateDecoder.decode(0x9900, 0x9902, 11205, payload))
    }
}
