// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real local encrypted lanes, simulated authorized receiver. No production radio is selected. */
class UltraRadioEncryptedIntegrationTest {
    @Test fun encryptedConfigRegistrationControlConfirmationAndSourceRetirement() {
        val now = AtomicLong(100_000_000_000L)
        val state = AtomicReference<UltraRadioBackend.Snapshot?>(UltraRadioBackend.Snapshot(now.get(), true,
            listOf(UltraRadioBackend.Station("one", "One", "One", "Ensemble"),
                UltraRadioBackend.Station("two", "Two", "Two", "Ensemble")), "one", true))
        val requested = CountDownLatch(1)
        val complete = AtomicReference<((Boolean) -> Unit)?>()
        val backend = object : UltraRadioBackend {
            override fun verifiedDabCapabilities() = UltraRadioBackend.Capabilities(setOf(UltraRadioBackend.CommandKind.TUNE_IDENTIFIER))
            override fun snapshot() = state.get()
            override fun request(command: UltraRadioBackend.Command, completion: (Boolean) -> Unit): Closeable {
                assertEquals("two", command.identifier)
                complete.set(completion)
                requested.countDown()
                return Closeable {}
            }
        }
        val loopback = InetAddress.getByName("127.0.0.1")
        val secret = ByteArray(32) { 19 }
        UltraRadioProtocol(backend, now::get).use { protocol ->
            lateinit var channels: VehicleStateChannels
            channels = VehicleStateChannels(secret, loopback, loopback,
                receive = { lane, id, bytes -> protocol.handle(lane, bytes).forEach { channels.send(it.lane, id, it.bytes) } },
                ended = { _, _ -> })
            channels.use {
                val high = channels.setup(VehicleStatePrototype.Lane.HIGH.uuid, 3, "61")!!
                val normal = channels.setup(VehicleStatePrototype.Lane.NORMAL.uuid, 4, "62")!!
                Peer(secret, loopback, high.port, "61").use { highPeer ->
                    Peer(secret, loopback, normal.port, "62").use { normalPeer ->
                        normalPeer.send("configRequest")
                        val config = normalPeer.read()
                        assertEquals(Opack.Text("configResponse"), config["command"])
                        val accessories = (config["values"] as Opack.Dict)["accessories"] as Opack.Array
                        assertEquals(2, accessories.values.size)
                        highPeer.send("registerRequest", Opack.Array(listOf(Opack.Integer(4102))))
                        assertEquals(Opack.Dict(listOf(Opack.Integer(4102) to Opack.Text("one"))), highPeer.read()["values"])
                        normalPeer.send("controlRequest", Opack.Dict(listOf(Opack.Integer(4364) to Opack.dict(
                            "identifier" to Opack.Text("two"), "sourceIdentifier" to Opack.Text("DAB")))))
                        assertTrue(requested.await(4, TimeUnit.SECONDS))
                        normalPeer.socket.soTimeout = 100
                        try { normalPeer.read(); fail("Command dispatch cannot confirm the receiver state") }
                        catch (_: SocketTimeoutException) { /* Waiting for a real observation. */ }
                        finally { normalPeer.socket.soTimeout = 4000 }
                        val observed = now.incrementAndGet()
                        state.set(state.get()!!.copy(observedAtNanos = observed, currentIdentifier = "two"))
                        complete.get()!!(true)
                        val update = protocol.poll() as UltraRadioProtocol.Poll.Messages
                        update.messages.forEach { message -> channels.send(message.lane,
                            if (message.lane == VehicleStatePrototype.Lane.HIGH) 3 else 4, message.bytes) }
                        assertEquals(Opack.Dict(listOf(Opack.Integer(4364) to Opack.Null)), normalPeer.read()["values"])
                        assertEquals(Opack.Dict(listOf(Opack.Integer(4102) to Opack.Text("two"))), highPeer.read()["values"])
                        state.set(null)
                        assertEquals(UltraRadioProtocol.Poll.SourceLost, protocol.poll())
                        channels.removeAll()
                        assertEquals(-1, highPeer.socket.getInputStream().read())
                        assertEquals(-1, normalPeer.socket.getInputStream().read())
                    }
                }
            }
        }
    }
    @Test fun aggregateRuntimeKeepsOneConfigAndMixedRegistrationThenRetiresBothEncryptedLanes() {
        val now = AtomicLong(100_000_000_000L)
        val state = AtomicReference<UltraRadioBackend.Snapshot?>(UltraRadioBackend.Snapshot(now.get(), true,
            listOf(UltraRadioBackend.Station("one", "One", "One", "Ensemble"),
                UltraRadioBackend.Station("two", "Two", "Two", "Ensemble")), "one", true))
        val range = AtomicReference(com.shilapi.xcertplay.transport.VehicleStatusSnapshot(227, null, 53.0, null, null, null, null))
        val requested = CountDownLatch(1)
        val complete = AtomicReference<((Boolean) -> Unit)?>()
        val backend = object : UltraRadioBackend {
            override fun verifiedDabCapabilities() = UltraRadioBackend.Capabilities(setOf(UltraRadioBackend.CommandKind.TUNE_IDENTIFIER))
            override fun snapshot() = state.get()
            override fun request(command: UltraRadioBackend.Command, completeCallback: (Boolean) -> Unit): Closeable {
                complete.set(completeCallback)
                requested.countDown()
                return Closeable {}
            }
        }
        val address = InetAddress.getByName("127.0.0.1")
        val secret = ByteArray(32) { 23 }
        VehicleStateRuntime(secret, com.shilapi.xcertplay.transport.VehicleStatusProvider { range.get() },
            address, address, {}, radio = backend, radioElapsedNanos = now::get).use { runtime ->
            val high = runtime.setup(VehicleStatePrototype.Lane.HIGH.uuid, 3, "71")!!
            val normal = runtime.setup(VehicleStatePrototype.Lane.NORMAL.uuid, 4, "72")!!
            Peer(secret, address, high.port, "71").use { highPeer ->
                Peer(secret, address, normal.port, "72").use { normalPeer ->
                    normalPeer.send("configRequest")
                    val config = normalPeer.read()
                    val accessories = (config["values"] as Opack.Dict)["accessories"] as Opack.Array
                    assertEquals(listOf(Opack.Integer(1792), Opack.Integer(4096), Opack.Integer(4352)),
                        accessories.values.map { (it as Opack.Dict)["iid"] })
                    highPeer.send("registerRequest", Opack.Array(listOf(Opack.Integer(1794), Opack.Integer(4102))))
                    assertEquals(Opack.Dict(listOf(Opack.Integer(1794) to Opack.Integer(227), Opack.Integer(4102) to Opack.Text("one"))),
                        highPeer.read()["values"])
                    normalPeer.send("controlRequest", Opack.Dict(listOf(Opack.Integer(4364) to Opack.dict(
                        "identifier" to Opack.Text("two"), "sourceIdentifier" to Opack.Text("DAB")))))
                    assertTrue(requested.await(4, TimeUnit.SECONDS))
                    range.set(range.get().copy(rangeKm = 220))
                    state.set(state.get()!!.copy(observedAtNanos = now.incrementAndGet(), currentIdentifier = "two"))
                    complete.get()!!(true)
                    assertEquals(Opack.Dict(listOf(Opack.Integer(4364) to Opack.Null)), normalPeer.read()["values"])
                    assertEquals(Opack.Dict(listOf(Opack.Integer(1794) to Opack.Integer(220), Opack.Integer(4102) to Opack.Text("two"))),
                        highPeer.read()["values"])
                    state.set(null) // Range is still valid: announced radio loss must close both lanes.
                    assertEquals(-1, highPeer.socket.getInputStream().read())
                    assertEquals(-1, normalPeer.socket.getInputStream().read())
                }
            }
        }
    }

    private class Peer(secret: ByteArray, address: InetAddress, port: Int, private val seed: String) : Closeable {
        val socket = Socket(address, port).apply { soTimeout = 4000 }
        private fun key(secret: ByteArray, direction: String) = AirPlayCrypto.hkdfSha512(secret,
            "DataStream-Salt$seed".toByteArray(), "DataStream-$direction-Encryption-Key".toByteArray())
        private val cipher = ControlCipher(key(secret, "Input"), key(secret, "Output"))
        private val parser = DataStreamPackage.Reader()
        fun send(command: String, values: Opack.Value = Opack.Null) {
            val inner = Opack.dict("command" to Opack.Text(command), "transactionID" to Opack.Integer(11), "values" to values)
            val encoded = Opack.encode(Opack.dict("pluginID" to Opack.Integer(0), "pluginData" to Opack.Data(Opack.encode(inner))))
            socket.getOutputStream().write(cipher.encrypt(DataStreamPackage.async(encoded)))
        }
        fun read(): Opack.Dict {
            val input = DataInputStream(socket.getInputStream())
            while (true) {
                val low = input.readUnsignedByte(); val high = input.readUnsignedByte()
                val sealed = ByteArray((low or (high shl 8)) + 16)
                input.readFully(sealed)
                val packet = parser.feed(cipher.decrypt(byteArrayOf(low.toByte(), high.toByte()) + sealed).data).firstOrNull() ?: continue
                val envelope = Opack.decode(packet.body) as Opack.Dict
                return Opack.decode((envelope["pluginData"] as Opack.Data).bytes()) as Opack.Dict
            }
        }
        override fun close() { socket.close() }
    }
}
