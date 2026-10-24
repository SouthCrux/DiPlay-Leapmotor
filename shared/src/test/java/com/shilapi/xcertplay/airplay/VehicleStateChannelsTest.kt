package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.VehicleStatePrototype.Lane
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class VehicleStateChannelsTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val secret = ByteArray(32) { 7 }
    private data class Received(val lane: Lane, val id: Long, val body: ByteArray)
    private val received = LinkedBlockingQueue<Received>()
    private val ended = LinkedBlockingQueue<Long>()
    private fun channels() = VehicleStateChannels(secret, loopback, loopback,
        receive = { lane, id, body -> received.add(Received(lane, id, body)) },
        ended = { _, id -> ended.add(id) },
    )

    @Test fun packageLayoutMatchesBigEndianReferenceAndHandlesFragmentation() {
        val packet = DataStreamPackage.pack(DataStreamPackage.SYNC, 0x636f6d6d, 0x0102030405060708,
            byteArrayOf(0xe0.toByte()))
        assertArrayEquals(hex("0000002173796e630000000000000000636f6d6d010203040506070800000000e0"), packet)
        val reader = DataStreamPackage.Reader()
        packet.dropLast(1).forEach { assertTrue(reader.feed(byteArrayOf(it)).isEmpty()) }
        val message = reader.feed(byteArrayOf(packet.last())).single()
        assertEquals(DataStreamPackage.SYNC, message.kind)
        assertEquals(0x0102030405060708, message.token)
        assertArrayEquals(byteArrayOf(0xe0.toByte()), message.body)
        assertEquals(2, reader.feed(DataStreamPackage.reply(1) + DataStreamPackage.reply(2)).size)
    }

    @Test fun badLengthsKindsAndErrorsFailBeforePayloadAccumulation() {
        listOf(0, 31, -1, Opack.MAX_BYTES + 33).forEach { size ->
            assertThrows(IllegalArgumentException::class.java) {
                DataStreamPackage.Reader().feed(ByteBuffer.allocate(4).putInt(size).array())
            }
        }
        val invalidKind = DataStreamPackage.pack(0, 0, 0, byteArrayOf())
        val failedReply = DataStreamPackage.reply(1).also { it[31] = 1 }
        listOf(invalidKind, failedReply).forEach {
            assertThrows(IllegalArgumentException::class.java) { DataStreamPackage.Reader().feed(it) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            DataStreamPackage.Reader().feed(ByteArray(65536))
        }
    }

    @Test fun bothLanesUseIndependentKeysAndCountersAndSyncIsAcknowledgedFirst() {
        channels().use { channels ->
            val high = channels.setup(Lane.HIGH.uuid, 2, "42")!!
            val normal = channels.setup(Lane.NORMAL.uuid, 3, "43")!!
            Phone(high.port, "42").use { a -> Phone(normal.port, "43").use { b ->
                val body = Opack.encode(Opack.dict("hello" to Opack.Integer(8)))
                val sync = DataStreamPackage.pack(DataStreamPackage.SYNC, 0x636f6d6d, 71, body)
                a.send(sync, fragmented = true)
                val acknowledgement = a.read()
                assertEquals(DataStreamPackage.REPLY, acknowledgement.kind)
                assertEquals(71L, acknowledgement.token)
                assertTrue(acknowledgement.body.isEmpty())
                val inbound = received.poll(3, TimeUnit.SECONDS)!!
                assertEquals(Lane.HIGH, inbound.lane)
                assertArrayEquals(body, inbound.body)
                assertTrue(channels.send(Lane.HIGH, 2, body))
                assertArrayEquals(body, a.read().body)
                b.send(DataStreamPackage.async(body))
                assertEquals(3L, received.poll(3, TimeUnit.SECONDS)!!.id)
                assertTrue(channels.send(Lane.NORMAL, 3, body))
                assertArrayEquals(body, b.read().body)
                assertFalse(channels.send(Lane.HIGH, 3, body))
            } }
        }
    }

    @Test fun largeBodiesCrossAeadFramesAndReplyPacketsAreNotDelivered() {
        channels().use { channels ->
            val bound = channels.setup(Lane.NORMAL.uuid, 2, "44")!!
            Phone(bound.port, "44").use { phone ->
                val body = ByteArray(50000) { (it % 251).toByte() }
                phone.send(DataStreamPackage.reply(5) + DataStreamPackage.async(body))
                assertArrayEquals(body, received.poll(3, TimeUnit.SECONDS)!!.body)
                assertTrue(received.isEmpty())
                assertTrue(channels.send(Lane.NORMAL, 2, body))
                assertArrayEquals(body, phone.read().body)
            }
        }
    }

    @Test fun replacementAndExactTeardownDoNotCloseOtherLanes() {
        channels().use { channels ->
            val high = channels.setup(Lane.HIGH.uuid, 2, "45")!!
            val normal = channels.setup(Lane.NORMAL.uuid, 3, "46")!!
            Phone(high.port, "45").use { old -> Phone(normal.port, "46").use { other ->
                old.send(DataStreamPackage.async(byteArrayOf(1)))
                assertNotNull(received.poll(3, TimeUnit.SECONDS))
                val replacement = channels.setup(Lane.HIGH.uuid, 4, "47")!!
                assertEquals(-1, old.socket.getInputStream().read())
                assertFalse(channels.isCurrent(Lane.HIGH, 2))
                assertTrue(channels.isCurrent(Lane.HIGH, 4))
                assertFalse(channels.send(Lane.HIGH, 2, byteArrayOf()))
                channels.remove(1)
                channels.remove(999)
                channels.remove(2)
                Phone(replacement.port, "47").use { fresh ->
                    fresh.send(DataStreamPackage.async(byteArrayOf(2)))
                    assertEquals(4L, received.poll(3, TimeUnit.SECONDS)!!.id)
                    channels.remove(4)
                    assertEquals(-1, fresh.socket.getInputStream().read())
                }
                other.send(DataStreamPackage.async(byteArrayOf(3)))
                assertEquals(3L, received.poll(3, TimeUnit.SECONDS)!!.id)
                assertTrue(channels.isCurrent(Lane.NORMAL, 3))
            } }
        }
    }

    @Test fun badTagAndReplayedFrameCloseOnlyTheAffectedChannel() {
        for (replay in listOf(false, true)) channels().use { channels ->
            val bound = channels.setup(Lane.HIGH.uuid, 2, "48")!!
            Phone(bound.port, "48").use { phone ->
                val frame = phone.encrypt(DataStreamPackage.async(byteArrayOf(1)))
                if (replay) {
                    phone.socket.getOutputStream().write(frame)
                    assertNotNull(received.poll(3, TimeUnit.SECONDS))
                } else frame[frame.lastIndex] = (frame.last().toInt() xor 1).toByte()
                phone.socket.getOutputStream().write(frame)
                assertEquals(2L, ended.poll(3, TimeUnit.SECONDS))
                assertEquals(-1, phone.socket.getInputStream().read())
                assertTrue(received.isEmpty())
                assertFalse(channels.isCurrent(Lane.HIGH, 2))
            }
        }
    }

    @Test fun setupRejectsUnknownRoutesCollisionsAndKeyReuseWithoutReplacingWorkingLane() {
        channels().use { channels ->
            assertNull(channels.setup("iAP-uuid", 1, "1"))
            channels.setup(Lane.HIGH.uuid, 2, "50")!!
            listOf(
                { channels.setup(Lane.NORMAL.uuid, 1, "51") },
                { channels.setup(Lane.NORMAL.uuid, 2, "51") },
                { channels.setup(Lane.HIGH.uuid, 3, "50") },
                { channels.setup(Lane.HIGH.uuid, 2, "51") },
                { channels.setup(Lane.HIGH.uuid, 3, "-1") },
                { channels.setup(Lane.HIGH.uuid, 3, "18446744073709551616") },
            ).forEach { assertThrows(IllegalArgumentException::class.java) { it() } }
            assertTrue(channels.isCurrent(Lane.HIGH, 2))
            channels.close()
            assertThrows(IllegalStateException::class.java) { channels.setup(Lane.HIGH.uuid, 3, "51") }
        }
    }

    private inner class Phone(port: Int, seed: String) : java.io.Closeable {
        val socket = Socket(loopback, port).apply { soTimeout = 3000; tcpNoDelay = true }
        private val cipher = ControlCipher(key(seed, "Input"), key(seed, "Output"))
        private val parser = DataStreamPackage.Reader()
        fun encrypt(bytes: ByteArray): ByteArray = cipher.encrypt(bytes)
        fun send(bytes: ByteArray, fragmented: Boolean = false) {
            val encrypted = encrypt(bytes)
            if (fragmented) encrypted.forEach { socket.getOutputStream().write(byteArrayOf(it)) }
            else socket.getOutputStream().write(encrypted)
        }
        fun read(): DataStreamPackage.Message {
            val input = DataInputStream(socket.getInputStream())
            while (true) {
                val low = input.readUnsignedByte()
                val high = input.readUnsignedByte()
                val sealed = ByteArray((low or (high shl 8)) + 16)
                input.readFully(sealed)
                val plain = cipher.decrypt(byteArrayOf(low.toByte(), high.toByte()) + sealed)
                val messages = parser.feed(plain.data)
                if (messages.isNotEmpty()) return messages.single()
            }
        }
        override fun close() = socket.close()
    }

    private fun key(seed: String, direction: String): ByteArray = AirPlayCrypto.hkdfSha512(secret,
        "DataStream-Salt$seed".toByteArray(), "DataStream-$direction-Encryption-Key".toByteArray())
    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
