package com.shilapi.xcertplay.airplay

/** Restricted binary-plist reader for nested UI Sync frames, including graph/cycle limits. */
internal object BoundedPlist {
    fun decode(bytes: ByteArray): Map<String, Any?>? = decodeLimited(bytes, Opack.MAX_BYTES)

    /** Local digest-pinned resource inventory only; network/UI Sync limits remain unchanged. */
    fun decodeResourceManifest(bytes: ByteArray): Map<String, Any?>? = decodeLimited(bytes, 1024 * 1024)

    private fun decodeLimited(bytes: ByteArray, maxBytes: Int): Map<String, Any?>? = try {
        @Suppress("UNCHECKED_CAST")
        Reader(bytes, maxBytes).readRoot() as? Map<String, Any?>
    } catch (_: IllegalArgumentException) { null }

    private class Reader(val bytes: ByteArray, val maxBytes: Int) {
        private val table: Int
        private val refs: Int
        private val offsets: IntArray
        private val root: Int
        private val active = mutableSetOf<Int>()
        private val cache = mutableMapOf<Int, Any?>()
        private var budget = 8192

        init {
            require(bytes.size in 40..maxBytes)
            require(bytes.copyOf(8).contentEquals("bplist00".toByteArray()))
            val trailer = bytes.size - 32
            val width = bytes[trailer + 6].toInt() and 255
            refs = bytes[trailer + 7].toInt() and 255
            require(width in 1..8 && refs in 1..8)
            val count = number(trailer + 8, 8).also { require(it in 1..8192) }.toInt()
            root = number(trailer + 16, 8).also { require(it in 0 until count.toLong()) }.toInt()
            table = number(trailer + 24, 8).also {
                require(it in 8..trailer.toLong() && it + count.toLong() * width <= trailer)
            }.toInt()
            offsets = IntArray(count) { i -> number(table + i * width, width).also {
                require(it in 8 until table.toLong())
            }.toInt() }
        }

        private fun number(at: Int, width: Int): Long {
            require(width in 1..8 && at >= 0 && at.toLong() + width <= bytes.size)
            var result = 0L
            repeat(width) { result = (result shl 8) or (bytes[at + it].toLong() and 255) }
            return result
        }

        fun readRoot(): Any? = read(root, 0)
        private fun read(index: Int, depth: Int): Any? {
            require(depth <= 32 && budget-- > 0 && index in offsets.indices && index !in active)
            if (cache.containsKey(index)) return cache[index]
            active += index
            var at = offsets[index]
            val marker = bytes[at++].toInt() and 255
            val type = marker ushr 4
            val nibble = marker and 15
            fun extent(count: Long): Int {
                require(count >= 0 && at.toLong() + count <= table)
                return count.toInt()
            }
            fun scalar(width: Int): Long {
                extent(width.toLong())
                return number(at, width).also { at += width }
            }
            fun count(): Int {
                if (nibble != 15) return nibble
                val sizeMarker = scalar(1).toInt()
                require(sizeMarker ushr 4 == 1 && (sizeMarker and 15) <= 3)
                return scalar(1 shl (sizeMarker and 15)).also { require(it in 0..maxBytes.toLong()) }.toInt()
            }
            val value: Any? = when (type) {
                0 -> when (nibble) { 0 -> null; 8 -> false; 9 -> true; else -> throw IllegalArgumentException() }
                // Eight-byte plist integers carry signed values such as appearanceMode=-1.
                1 -> { require(nibble <= 3); scalar(1 shl nibble) }
                2 -> when (nibble) {
                    2 -> Float.fromBits(scalar(4).toInt()).toDouble()
                    3 -> Double.fromBits(scalar(8))
                    else -> throw IllegalArgumentException()
                }.also { require(it.isFinite()) }
                4 -> count().let { bytes.copyOfRange(at, at + extent(it.toLong())) }
                5, 6 -> {
                    val length = count()
                    val size = extent(length.toLong() * if (type == 6) 2 else 1)
                    if (type == 5) require(bytes.copyOfRange(at, at + size).all { it >= 0 })
                    String(bytes, at, size, if (type == 6) Charsets.UTF_16BE else Charsets.US_ASCII)
                }
                8 -> { require(nibble < 8); scalar(nibble + 1) }
                10, 13 -> {
                    val count = count().also { require(it <= 8192) }
                    extent(count.toLong() * refs * if (type == 13) 2 else 1)
                    fun ref(position: Int): Int = number(position, refs).also {
                        require(it in offsets.indices.first.toLong()..offsets.indices.last.toLong())
                    }.toInt()
                    if (type == 10) List(count) { read(ref(at + it * refs), depth + 1) }
                    else linkedMapOf<String, Any?>().apply {
                        repeat(count) { i ->
                            val key = read(ref(at + i * refs), depth + 1) as? String ?: throw IllegalArgumentException()
                            require(!containsKey(key))
                            put(key, read(ref(at + (count + i) * refs), depth + 1))
                        }
                    }
                }
                else -> throw IllegalArgumentException()
            }
            active -= index
            cache[index] = value
            return value
        }
    }
}
