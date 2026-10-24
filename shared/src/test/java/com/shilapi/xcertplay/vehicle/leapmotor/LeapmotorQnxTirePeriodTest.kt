package com.shilapi.xcertplay.vehicle.leapmotor

import org.junit.Assert.*
import org.junit.Test

class LeapmotorQnxTirePeriodTest {
    private fun field(tag: Int, body: ByteArray) = byteArrayOf(tag.toByte(), body.size.toByte()) + body
    private fun event(id: String, value: String) = field(34,
        field(10, id.toByteArray()) + field(18, value.toByteArray()))

    @Test fun extractsOnlyTyresFromOemEnvelopeAndPreservesStatus() {
        val packet = field(10, "qnx".toByteArray()) + event("1006", "private") +
            event("1109", "231|0") + event("1116", "-1|0") + event("1110", "228|1")
        val fields = LeapmotorQnxTirePeriod.decode(packet)
        assertEquals(listOf(1109, 1116, 1110), fields.map { it.event })
        assertEquals(listOf("231|0", "-1|0", "228|1"), fields.map { it.payload!!.toString(Charsets.UTF_8) })
    }

    @Test fun rejectsTruncatedOversizedAndInvalidPacketsWithoutPartialResults() {
        val valid = event("1109", "231|0")
        listOf(valid + byteArrayOf(34, 99), valid + byteArrayOf(0),
            valid + byteArrayOf(34, -1, -1, -1, -1, 127), ByteArray(65_537)).forEach {
            assertTrue(LeapmotorQnxTirePeriod.decode(it).isEmpty())
        }
    }

    @Test fun overlongTyreValueWithdrawsThatFieldAndUnknownWireFieldsAreSkipped() {
        val packet = byteArrayOf(40, 1, 49, 0, 0, 0, 0, 0, 0, 0, 0, 61, 0, 0, 0, 0) +
            event("1109", "1".repeat(33)) + event("1110", "228|0")
        val decoded = LeapmotorQnxTirePeriod.decode(packet)
        assertEquals(2, decoded.size)
        assertNull(decoded.first().payload)
        assertEquals("228|0", decoded.last().payload!!.toString(Charsets.UTF_8))
    }
}
