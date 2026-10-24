package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class BoundedPlistTest {
    @Test fun transitionPresentationTimePreservesFractionalSeconds() {
        val bytes = BplistCodec.encode(mapOf("type" to 2L, "data" to mapOf("pts" to 123456.125)))
        val decoded = BoundedPlist.decode(bytes)!!
        assertEquals(2L, decoded["type"])
        val data = decoded["data"] as Map<*, *>
        assertEquals(123456.125, data["pts"] as Double, 0.0)
    }

    @Test fun independentlyEncodedFloat32AndFloat64AreAccepted() {
        // Fixed bplist dictionaries {"pts": 1.25}, independent of BplistCodec's real writer.
        assertEquals(1.25, BoundedPlist.decode(fixture(byteArrayOf(0x22, 0x3f, 0xa0.toByte(), 0, 0)))!!["pts"])
        assertEquals(1.25, BoundedPlist.decode(fixture(byteArrayOf(0x23) + long(1.25.toRawBits())))!!["pts"])
    }

    @Test fun signedEightByteIntegerAndUnsignedShortIntegerRemainExact() {
        assertEquals(-1L, BoundedPlist.decode(fixture(byteArrayOf(0x13) + long(-1)))!!["pts"])
        assertEquals(Long.MIN_VALUE, BoundedPlist.decode(fixture(byteArrayOf(0x13) + long(Long.MIN_VALUE)))!!["pts"])
        assertEquals(255L, BoundedPlist.decode(fixture(byteArrayOf(0x10, 0xff.toByte())))!!["pts"])
    }

    private fun fixture(value: ByteArray): ByteArray {
        val objects = byteArrayOf(0xd1.toByte(), 1, 2, 0x53, 0x70, 0x74, 0x73) + value
        val table = 8 + objects.size
        return "bplist00".toByteArray() + objects + byteArrayOf(8, 11, 15) + ByteArray(6) +
            byteArrayOf(1, 1) + long(3) + long(0) + long(table.toLong())
    }

    @Test fun nonfiniteAndTruncatedRealValuesAreRejected() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(BoundedPlist.decode(BplistCodec.encode(mapOf("pts" to value))))
        }
        val bytes = BplistCodec.encode(mapOf("pts" to 1.25))
        bytes.indices.forEach { assertNull(BoundedPlist.decode(bytes.copyOf(it))) }
    }

    @Test fun localResourceInventoryHasASeparateLimitWithoutExpandingNetworkFrames() {
        val medium = BplistCodec.encode(mapOf("fixture" to ByteArray(300 * 1024)))
        assertNull(BoundedPlist.decode(medium))
        assertEquals(300 * 1024, (BoundedPlist.decodeResourceManifest(medium)!!["fixture"] as ByteArray).size)
        val excessive = BplistCodec.encode(mapOf("fixture" to ByteArray(1024 * 1024)))
        assertNull(BoundedPlist.decodeResourceManifest(excessive))
        assertNull(BoundedPlist.decode(excessive))
    }

    private fun long(value: Long) = ByteArray(8) { (value ushr ((7 - it) * 8)).toByte() }
}
