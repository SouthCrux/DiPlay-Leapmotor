// SPDX-License-Identifier: GPL-3.0-only
// Framing reference: f-io/LIVI 4bcd1335, livi-cp/src/iap_tunnel.rs.
package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ArrayBlockingQueue

/** One authenticated duplex type-130 connection, owned by the experimental session runtime. */
internal class VehicleStateChannel(
    sharedSecret: ByteArray,
    seed: String,
    bindAddress: InetAddress,
    private val expectedPeer: InetAddress,
    private val receive: (ByteArray) -> Unit,
    private val ended: () -> Unit,
) : Closeable {
    private val readKey: ByteArray
    private val writeKey: ByteArray
    private val closed = AtomicBoolean()
    private val writeLock = Any()
    private var writeCounter = 0L
    private val server = ServerSocket()
    @Volatile private var peer: Socket? = null
    private val outgoing = ArrayBlockingQueue<ByteArray>(16)
    @Volatile private var writer: Thread? = null
    val port: Int get() = server.localPort
    val connected: Boolean get() = !closed.get() && peer != null

    init {
        require(sharedSecret.size == 32)
        require(seed.matches(Regex("0|[1-9][0-9]{0,19}")) && seed.toULongOrNull() != null)
        val salt = "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII)
        readKey = AirPlayCrypto.hkdfSha512(sharedSecret, salt, "DataStream-Output-Encryption-Key".toByteArray())
        writeKey = AirPlayCrypto.hkdfSha512(sharedSecret, salt, "DataStream-Input-Encryption-Key".toByteArray())
        try {
            server.bind(InetSocketAddress(bindAddress, 0))
        } catch (error: Exception) {
            server.close()
            throw error
        }
    }

    /** Start only once, after the channel owner has published its identity. */
    fun start() {
        check(started.compareAndSet(false, true))
        Thread(::run, "diplay-vehicle-state").apply { isDaemon = true; start() }
    }
    private val started = AtomicBoolean()

    fun send(body: ByteArray): Boolean = enqueue(DataStreamPackage.async(body))

    private fun enqueue(bytes: ByteArray): Boolean {
        if (closed.get() || peer == null) return false
        if (outgoing.offer(bytes)) return true
        close()
        return false
    }

    private fun write(packageBytes: ByteArray): Boolean = synchronized(writeLock) {
        val socket = peer ?: return false
        if (closed.get()) return false
        try {
            val output = socket.getOutputStream()
            var at = 0
            while (at < packageBytes.size) {
                check(writeCounter >= 0 && writeCounter < Long.MAX_VALUE)
                val count = minOf(16 * 1024, packageBytes.size - at)
                val aad = byteArrayOf(count.toByte(), (count ushr 8).toByte())
                val sealed = AirPlayCrypto.chachaSeal(writeKey, AirPlayCrypto.nonce64(writeCounter++),
                    packageBytes.copyOfRange(at, at + count), aad)
                output.write(aad)
                output.write(sealed)
                at += count
            }
            output.flush()
            true
        } catch (_: Exception) {
            close()
            false
        }
    }

    private fun run() {
        try {
            // Unrelated peers cannot consume the single connection or reset its nonce counters.
            while (!closed.get()) {
                val socket = server.accept()
                if (socket.inetAddress != expectedPeer || closed.get()) { socket.close(); continue }
                peer = socket
                if (closed.get()) { socket.close(); return }
                server.close()
                socket.tcpNoDelay = true
                writer = Thread({
                    try {
                        while (!closed.get()) if (!write(outgoing.take())) return@Thread
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally { close() }
                }, "diplay-vehicle-state-send").apply { isDaemon = true; start() }
                read(socket)
                return
            }
        } catch (_: Exception) {
            // The owner receives closure only. Never log keys, payloads or arbitrary peer errors.
        } finally {
            close()
            ended()
        }
    }

    private fun read(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val parser = DataStreamPackage.Reader()
        var counter = 0L
        while (!closed.get()) {
            val low = input.read()
            if (low < 0) return
            val high = input.read()
            if (high < 0) throw EOFException()
            val count = low or (high shl 8)
            require(count > 0)
            val encrypted = ByteArray(count + 16)
            input.readFully(encrypted)
            check(counter >= 0 && counter < Long.MAX_VALUE)
            val plain = AirPlayCrypto.chachaOpen(readKey, AirPlayCrypto.nonce64(counter++), encrypted,
                byteArrayOf(low.toByte(), high.toByte()))
            for (message in parser.feed(plain)) {
                if (message.kind == DataStreamPackage.REPLY) continue
                if (message.kind == DataStreamPackage.SYNC && !enqueue(DataStreamPackage.reply(message.token))) return
                receive(message.body)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        runCatching { peer?.close() }
        writer?.interrupt()
        outgoing.clear()
    }
}

/** Big-endian APTransportPackage, inside independent ChaCha20-Poly1305 frames. */
internal object DataStreamPackage {
    const val ASYNC = 0x6173796e
    const val SYNC = 0x73796e63
    const val REPLY = 0x72706c79
    private const val HEADER = 32
    private const val MAX_PACKAGE = Opack.MAX_BYTES + HEADER
    data class Message(val kind: Int, val token: Long, val body: ByteArray)

    fun async(body: ByteArray): ByteArray = pack(ASYNC, 0x636d6e64, 0, body)
    fun reply(token: Long): ByteArray = pack(REPLY, 0, token, byteArrayOf())
    internal fun pack(kind: Int, type: Int, token: Long, body: ByteArray): ByteArray {
        require(body.size <= Opack.MAX_BYTES)
        return ByteBuffer.allocate(HEADER + body.size).putInt(HEADER + body.size).putInt(kind)
            .putLong(0).putInt(type).putLong(token).putInt(0).put(body).array()
    }

    class Reader {
        private var pending = ByteArray(0)
        fun feed(bytes: ByteArray): List<Message> {
            require(bytes.size <= 65535 && pending.size + bytes.size <= MAX_PACKAGE + 65535)
            val buffer = pending + bytes
            val messages = mutableListOf<Message>()
            var at = 0
            while (buffer.size - at >= 4) {
                val size = ByteBuffer.wrap(buffer, at, 4).int
                require(size in HEADER..MAX_PACKAGE)
                if (buffer.size - at < size) break
                val header = ByteBuffer.wrap(buffer, at, HEADER)
                header.int
                val kind = header.int
                require(kind == ASYNC || kind == SYNC || kind == REPLY)
                header.long // group/reserved
                header.int // message type; LIVI dispatches all non-reply message bodies
                val token = header.long
                require(header.int == 0) // peer-reported package error
                messages += Message(kind, token, buffer.copyOfRange(at + HEADER, at + size))
                at += size
            }
            pending = buffer.copyOfRange(at, buffer.size)
            return messages
        }
    }
}
