// SPDX-License-Identifier: GPL-3.0-only
// Wire format reference: f-io/LIVI, revision 4bcd1335, livi-cp/src/opack.rs.
package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded OPACK messages. No collection back-references or implicit trailing records. */
internal object Opack {
    const val MAX_BYTES = 256 * 1024
    private const val MAX_DEPTH = 32
    private const val MAX_VALUES = 8192
    private const val MAX_REFERENCES = 4096

    sealed interface Value
    data object Null : Value
    data class Bool(val value: Boolean) : Value
    data class Integer(val value: Long) : Value
    data class Real(val value: Double) : Value
    data class Date(val seconds: Double) : Value
    data class Text(val value: String) : Value
    class Data(bytes: ByteArray) : Value {
        private val content = bytes.copyOf()
        fun bytes(): ByteArray = content.copyOf()
        override fun equals(other: Any?): Boolean = other is Data && content.contentEquals(other.content)
        override fun hashCode(): Int = content.contentHashCode()
    }
    data class Uuid(val value: java.util.UUID) : Value
    data class Array(val values: List<Value>) : Value
    data class Dict(val entries: List<Pair<Value, Value>>) : Value {
        operator fun get(key: String): Value? = entries.firstOrNull { it.first == Text(key) }?.second
    }

    fun dict(vararg entries: Pair<String, Value>): Dict =
        Dict(entries.map { Text(it.first) to it.second })

    /** Malformed, ambiguous, oversized or excessively nested peer input is rejected. */
    fun decode(bytes: ByteArray): Value? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return try {
            Reader(bytes).run { value(0).takeIf { position == bytes.size } }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun encode(value: Value): ByteArray = Writer().run { value(value, 0); output.toByteArray() }

    private class Reader(private val input: ByteArray) {
        var position = 0
        private var remainingValues = MAX_VALUES
        private val references = mutableListOf<Value>()

        private fun take(count: Int): ByteArray {
            require(count >= 0 && count <= input.size - position)
            return input.copyOfRange(position, position + count).also { position += count }
        }

        private fun unsigned(width: Int): Long {
            require(width in 1..8)
            val bytes = take(width)
            var result = 0L
            for (i in bytes.indices) result = result or ((bytes[i].toLong() and 255) shl (8 * i))
            return result
        }

        private fun length(tag: Int, base: Int): Int {
            val short = tag - base
            val count = if (short <= 32) short.toLong() else unsigned(short - 32)
            require(count in 0..MAX_BYTES.toLong())
            return count.toInt()
        }

        private fun remember(value: Value): Value {
            require(references.size < MAX_REFERENCES)
            references += value
            return value
        }

        fun value(depth: Int): Value {
            require(depth <= MAX_DEPTH && remainingValues-- > 0)
            val tag = unsigned(1).toInt()
            return when (tag) {
                1 -> Bool(true)
                2 -> Bool(false)
                4 -> Null
                5 -> remember(Uuid(ByteBuffer.wrap(take(16)).let { java.util.UUID(it.long, it.long) }))
                6 -> remember(Date(Double.fromBits(unsigned(8))))
                in 8..47 -> Integer((tag - 8).toLong())
                in 0x30..0x33 -> {
                    val width = 1 shl (tag - 0x30)
                    val shift = 64 - 8 * width
                    remember(Integer((unsigned(width) shl shift) shr shift))
                }
                0x35 -> remember(Real(Float.fromBits(unsigned(4).toInt()).toDouble()))
                0x36 -> remember(Real(Double.fromBits(unsigned(8))))
                in 0x40..0x64 -> {
                    val bytes = take(length(tag, 0x40))
                    val decoder = Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    val text = try { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
                        catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("UTF-8") }
                    remember(Text(text))
                }
                in 0x70..0x94 -> remember(Data(take(length(tag, 0x70))))
                in 0xa0..0xc4 -> {
                    val index = if (tag <= 0xc0) (tag - 0xa0).toLong() else unsigned(tag - 0xc0)
                    require(index >= 0 && index < references.size)
                    references[index.toInt()]
                }
                in 0xd0..0xef -> {
                    val count = tag and 15
                    val open = count == 15
                    val dictionary = tag >= 0xe0
                    val items = mutableListOf<Value>()
                    val entries = mutableListOf<Pair<Value, Value>>()
                    val keys = mutableSetOf<Value>()
                    var size = 0
                    while (open || size < count) {
                        if (open && position < input.size && input[position] == 3.toByte()) {
                            position++
                            break
                        }
                        val first = value(depth + 1)
                        if (dictionary) {
                            // Keys used by the vehicle protocol are scalar; reject ambiguous duplicate keys.
                            require(first !is Dict && first !is Array && keys.add(first))
                            entries += first to value(depth + 1)
                        } else items += first
                        size++
                    }
                    if (dictionary) Dict(entries) else Array(items)
                }
                else -> throw IllegalArgumentException("OPACK tag")
            }
        }
    }

    private class Writer {
        val output = ByteArrayOutputStream()
        private var remainingValues = MAX_VALUES
        private var references = 0

        private fun byte(value: Int) {
            require(output.size() < MAX_BYTES)
            output.write(value)
        }

        private fun bytes(value: ByteArray) {
            require(value.size <= MAX_BYTES - output.size())
            output.write(value)
        }

        private fun little(value: Long, width: Int) {
            repeat(width) { byte((value ushr (8 * it)).toInt() and 255) }
        }

        private fun length(base: Int, count: Int) {
            if (count <= 32) byte(base + count) else {
                val width = if (count <= 255) 1 else if (count <= 65535) 2 else 3
                byte(base + 32 + width)
                little(count.toLong(), width)
            }
        }

        fun value(value: Value, depth: Int) {
            require(depth <= MAX_DEPTH && remainingValues-- > 0)
            if (value is Text || value is Data || value is Uuid || value is Date || value is Real ||
                (value is Integer && value.value !in 0..39)) require(references++ < MAX_REFERENCES)
            when (value) {
                Null -> byte(4)
                is Bool -> byte(if (value.value) 1 else 2)
                is Integer -> {
                    val n = value.value
                    if (n in 0..39) byte(8 + n.toInt()) else {
                        val width = when (n) {
                            in -128..127 -> 1
                            in -32768..32767 -> 2
                            in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> 4
                            else -> 8
                        }
                        byte(0x30 + java.lang.Integer.numberOfTrailingZeros(width))
                        little(n, width)
                    }
                }
                is Real -> { byte(0x36); little(value.value.toBits(), 8) }
                is Date -> { byte(6); little(value.seconds.toBits(), 8) }
                is Text -> value.value.toByteArray(Charsets.UTF_8).let { length(0x40, it.size); bytes(it) }
                is Data -> value.bytes().let { length(0x70, it.size); bytes(it) }
                is Uuid -> {
                    byte(5)
                    bytes(ByteBuffer.allocate(16).putLong(value.value.mostSignificantBits)
                        .putLong(value.value.leastSignificantBits).array())
                }
                is Array -> {
                    byte(0xd0 + minOf(15, value.values.size))
                    value.values.forEach { value(it, depth + 1) }
                    if (value.values.size >= 15) byte(3)
                }
                is Dict -> {
                    byte(0xe0 + minOf(15, value.entries.size))
                    val keys = mutableSetOf<Value>()
                    value.entries.forEach { (key, item) ->
                        require(key !is Dict && key !is Array && keys.add(key))
                        value(key, depth + 1)
                        value(item, depth + 1)
                    }
                    if (value.entries.size >= 15) byte(3)
                }
            }
        }
    }
}
