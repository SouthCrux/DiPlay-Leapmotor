package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.Opack.Array
import com.shilapi.xcertplay.airplay.Opack.Bool
import com.shilapi.xcertplay.airplay.Opack.Data
import com.shilapi.xcertplay.airplay.Opack.Date
import com.shilapi.xcertplay.airplay.Opack.Dict
import com.shilapi.xcertplay.airplay.Opack.Integer
import com.shilapi.xcertplay.airplay.Opack.Real
import com.shilapi.xcertplay.airplay.Opack.Text
import com.shilapi.xcertplay.airplay.Opack.Uuid
import com.shilapi.xcertplay.airplay.Opack.Value
import org.junit.Assert.*
import org.junit.Test

class OpackTest {
    @Test fun referenceFixtureResolvesRepeatedSignedErrors() {
        // LIVI 4bcd1335 opack.rs registerResponse fixture, GPL-3.0.
        val packet = hex("""
            e4466572726f7273eb31040131b9e5310d01a2310501a2310e01a2310a01a2310601a2310201a2
            310f01a2310b01a2310301a2310c01a24d7472616e73616374696f6e494433a0bc52cb03e5cd02
            47636f6d6d616e64507265676973746572526573706f6e73654676616c756573e23108010131110108
        """)
        val response = Opack.decode(packet) as Dict
        assertEquals(Text("registerResponse"), response["command"])
        val errors = (response["errors"] as Dict).entries
        assertEquals(11, errors.size)
        assertTrue(errors.all { it.second == Integer(-6727) })
        assertEquals(Integer(269), errors[1].first)
        assertEquals(Dict(listOf(Integer(264) to Bool(true), Integer(273) to Integer(0))), response["values"])
    }

    @Test fun signedWidthsAndExtendedLengthsMatchWireBytes() {
        assertArrayEquals(hex("0d"), Opack.encode(Integer(5)))
        assertArrayEquals(hex("31ff00"), Opack.encode(Integer(255)))
        assertArrayEquals(hex("31b9e5"), Opack.encode(Integer(-6727)))
        assertArrayEquals(hex("32ffff0000"), Opack.encode(Integer(65535)))
        assertArrayEquals(hex("33ffffffff00000000"), Opack.encode(Integer(0xffff_ffffL)))
        val text = Text("é".repeat(150))
        val encoded = Opack.encode(text)
        assertArrayEquals(hex("622c01"), encoded.copyOf(3))
        assertEquals(text, Opack.decode(encoded))
        listOf(Long.MIN_VALUE, Long.MAX_VALUE, -128L, -129L, 39L, 40L).forEach {
            assertEquals(Integer(it), Opack.decode(Opack.encode(Integer(it))))
        }
    }

    @Test fun scalarReferencesDoNotIncludeSmallNumbersOrCollections() {
        val packet = hex("e3436969643111014474797065a1446c697374d3a00dc102")
        val decoded = Opack.decode(packet) as Dict
        assertEquals(Integer(273), decoded["type"])
        assertEquals(Array(listOf(Text("iid"), Integer(5), Text("type"))), decoded["list"])
        assertEquals(Array(listOf(Real(1.5), Real(1.5))), Opack.decode(hex("d2350000c03fa0")))
    }

    @Test fun everyValueTypeAndOpenContainersRoundTrip() {
        val value = Array(listOf(
            Opack.Null, Bool(false), Data(byteArrayOf(0, -1, 4)),
            Uuid(java.util.UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")),
            Date(123.25), Real(-2.75), Dict((0L..19).map { Integer(it) to Text("key$it") }),
        ) + (0L..19).map(::Integer))
        assertEquals(value, Opack.decode(Opack.encode(value)))
        assertEquals(Date(1.0), Opack.decode(hex("06000000000000f03f")))
    }

    @Test fun malformedAmbiguousAndTruncatedPacketsAreRejected() {
        listOf("", "03", "a0", "c1ff", "e1416b", "df08", "ef416b03", "0404", "41ff",
            "e2416b08416b09", "e2a008a009", "64ffffffff", "94ffffffff", "c4ffffffff", "d105").forEach {
            assertNull(it, Opack.decode(hex(it)))
        }
        val valid = Opack.encode(Opack.dict("value" to Text("hello")))
        valid.indices.forEach { assertNull("truncated at $it", Opack.decode(valid.copyOf(it))) }
    }

    @Test fun depthValueReferenceAndByteBudgetsAreEnforced() {
        assertNull(Opack.decode(ByteArray(40) { 0xd1.toByte() } + byteArrayOf(4)))
        assertNull(Opack.decode(byteArrayOf(0xdf.toByte()) + ByteArray(8192) { 4 } + byteArrayOf(3)))
        assertNull(Opack.decode(byteArrayOf(0xdf.toByte()) + ByteArray(4097) { 0x40 } + byteArrayOf(3)))
        assertNull(Opack.decode(ByteArray(Opack.MAX_BYTES + 1)))
        assertThrows(IllegalArgumentException::class.java) { Opack.encode(Data(ByteArray(Opack.MAX_BYTES))) }
        assertThrows(IllegalArgumentException::class.java) { Opack.encode(Array(List(8192) { Opack.Null })) }
        assertThrows(IllegalArgumentException::class.java) { Opack.encode(Array(List(4097) { Text("") })) }
    }

    @Test fun binaryValuesOwnTheirBytes() {
        val input = byteArrayOf(1, 2)
        val data = Data(input)
        input[0] = 5
        data.bytes()[1] = 9
        assertArrayEquals(byteArrayOf(1, 2), data.bytes())
    }

    private fun hex(text: String): ByteArray = text.filterNot(Char::isWhitespace).chunked(2)
        .map { it.toInt(16).toByte() }.toByteArray()
}
