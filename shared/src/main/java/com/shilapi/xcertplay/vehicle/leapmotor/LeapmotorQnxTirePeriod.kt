package com.shilapi.xcertplay.vehicle.leapmotor

/** Read-only subset of the OEM periodic protobuf. No generated OEM code or unrelated values retained.
 * CarControlEvent.events is field 4; Event.event/value are string fields 1/2.
 * Input here is the decoded Base64 body of service 0x7201, instance 0x7202, event 999.
 */
object LeapmotorQnxTirePeriod {
    const val MAX_BYTES = 65_536
    data class Field(val event: Int, val payload: ByteArray?)

    fun decode(bytes: ByteArray): List<Field> = try {
        require(bytes.size <= MAX_BYTES)
        val input = Reader(bytes)
        val results = mutableListOf<Field>()
        var count = 0
        while (!input.done()) {
            require(++count <= 4096)
            val tag = input.number()
            if (tag == 34) {
                val item = Reader(input.block())
                var event: Int? = null
                var value: ByteArray? = null
                var itemCount = 0
                while (!item.done()) {
                    require(++itemCount <= 64)
                    when (val part = item.number()) {
                        10 -> event = item.block().takeIf { it.size == 4 && it.all { b -> b in 48..57 } }
                            ?.toString(Charsets.US_ASCII)?.toIntOrNull()
                        18 -> value = item.block().takeIf { it.size <= 32 }
                        else -> item.skip(part)
                    }
                }
                if (event != null && event in 1109..1116) {
                    require(results.size < 512)
                    results += Field(event, value)
                }
            } else input.skip(tag)
        }
        results
    } catch (_: IllegalArgumentException) { emptyList() }

    private class Reader(private val bytes: ByteArray) {
        private var at = 0
        fun done() = at == bytes.size
        fun number(): Int {
            var result = 0L
            for (shift in 0..28 step 7) {
                require(at < bytes.size)
                val b = bytes[at++].toInt() and 255
                result = result or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) { require(result <= Int.MAX_VALUE); return result.toInt() }
            }
            throw IllegalArgumentException("Oversized varint")
        }
        fun block(): ByteArray {
            val size = number()
            require(size <= bytes.size - at)
            return bytes.copyOfRange(at, at + size).also { at += size }
        }
        fun skip(tag: Int) {
            require(tag ushr 3 > 0)
            when (tag and 7) {
                0 -> {
                    var length = 0
                    do { require(at < bytes.size && ++length <= 10) }
                    while (bytes[at++].toInt() and 128 != 0)
                }
                1 -> { require(bytes.size - at >= 8); at += 8 }
                2 -> { val size = number(); require(size <= bytes.size - at); at += size }
                5 -> { require(bytes.size - at >= 4); at += 4 }
                else -> throw IllegalArgumentException("Unsupported wire type")
            }
        }
    }
}
