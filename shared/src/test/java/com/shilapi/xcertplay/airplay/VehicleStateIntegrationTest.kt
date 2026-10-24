package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import java.net.InetAddress
import java.net.Socket
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VehicleStateIntegrationTest {
    @Test fun defaultOffAndAbsentProposalNeverEnableResearchFeatures() {
        val off = config(false)
        assertFalse(AirPlayInfoPlist.build(off).containsKey("vehicleStateProtocolInfo"))
        assertFalse("vehicleStateProtocol" in setupEnabledFeatures(off, listOf("vehicleStateProtocol")))
        val on = config(true)
        assertTrue(AirPlayInfoPlist.build(on).containsKey("vehicleStateProtocolInfo"))
        assertFalse("vehicleStateProtocol" in setupEnabledFeatures(on, null))
        val features = setupEnabledFeatures(on, listOf("vehicleStateProtocol", "uiSync", "fileTransfer"))
        assertTrue("vehicleStateProtocol" in features)
        assertFalse("uiSync" in features)
        assertFalse("fileTransfer" in features)
    }

    @Test fun sessionWithoutProviderDoesNotAdvertiseAnUnserviceableProtocol() {
        val session = session(object : AirPlayMediaHandler {})
        try {
            val response = handle(session, "GET", "/info", byteArrayOf())
            val info = BplistCodec.decode(response.body) as Map<*, *>
            assertFalse(info.containsKey("vehicleStateProtocolInfo"))
            handle(session, "SETUP", "/", BplistCodec.encode(mapOf("features" to listOf("vehicleStateProtocol"))))
            assertFalse(session.vehicleStateNegotiated)
        } finally { session.close() }
    }

    @Test fun exactDataStreamTeardownIsForwardedWithoutTearingDownIap() {
        val ids = mutableListOf<Long?>()
        val types = mutableListOf<Int>()
        val session = session(object : AirPlayMediaHandler {
            override fun onDataStreamTeardown(session: AirPlaySession, streamId: Long?) { ids += streamId }
            override fun onTeardown(session: AirPlaySession, type: Int) { types += type }
        })
        try {
            session.activeStreams.add(130)
            handle(session, "TEARDOWN", "/", BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 130, "streamID" to 7)))))
            assertEquals(listOf(7L), ids)
            assertTrue(types.isEmpty())
            assertTrue(130 in session.activeStreams)
            handle(session, "TEARDOWN", "/", BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 130, "streamID" to 1)))))
            assertEquals(listOf(130), types)
        } finally { session.close() }
    }

    @Test fun runtimeAnswersEncryptedConfigAndRegistrationAndSendsFreshRange() {
        val address = InetAddress.getByName("127.0.0.1")
        val secret = ByteArray(32) { 7 }
        val state = AtomicReference<VehicleStatusSnapshot?>(VehicleStatusSnapshot(227, null, 53.1, null, null, null, null))
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sourceLost = java.util.concurrent.CountDownLatch(1)
        VehicleStateRuntime(secret, VehicleStatusProvider { state.get() }, address, address, { message ->
            logs += message
            if ("source unavailable" in message) sourceLost.countDown()
        }).use { runtime ->
            val bound = runtime.setup(VehicleStatePrototype.Lane.HIGH.uuid, 3, "42")!!
            Socket(address, bound.port).use { socket ->
                socket.soTimeout = 4000
                fun key(direction: String) = AirPlayCrypto.hkdfSha512(secret, "DataStream-Salt42".toByteArray(),
                    "DataStream-$direction-Encryption-Key".toByteArray())
                val cipher = ControlCipher(key("Input"), key("Output"))
                val parser = DataStreamPackage.Reader()
                fun send(command: String, values: Opack.Value = Opack.Null) {
                    val inner = Opack.dict("command" to Opack.Text(command), "transactionID" to Opack.Integer(8), "values" to values)
                    val outer = Opack.encode(Opack.dict("pluginID" to Opack.Integer(0), "pluginData" to Opack.Data(Opack.encode(inner))))
                    socket.getOutputStream().write(cipher.encrypt(DataStreamPackage.async(outer)))
                }
                fun read(): Opack.Dict {
                    val input = DataInputStream(socket.getInputStream())
                    while (true) {
                        val low = input.readUnsignedByte(); val high = input.readUnsignedByte()
                        val encrypted = ByteArray((low or (high shl 8)) + 16); input.readFully(encrypted)
                        val plain = cipher.decrypt(byteArrayOf(low.toByte(), high.toByte()) + encrypted)
                        val packet = parser.feed(plain.data).firstOrNull() ?: continue
                        val outer = Opack.decode(packet.body) as Opack.Dict
                        return Opack.decode((outer["pluginData"] as Opack.Data).bytes()) as Opack.Dict
                    }
                }
                send("configRequest")
                assertEquals(Opack.Text("configResponse"), read()["command"])
                send("registerRequest", Opack.Array(listOf(Opack.Integer(1794))))
                assertEquals(Opack.Text("registerResponse"), read()["command"])
                state.set(state.get()!!.copy(rangeKm = 226))
                val notification = read()
                assertEquals(Opack.Text("updateNotify"), notification["command"])
                assertEquals(Opack.Dict(listOf(Opack.Integer(1794) to Opack.Integer(226))), notification["values"])
                state.set(null)
                assertEquals(-1, socket.getInputStream().read())
                // EOF precedes the reset log; wait for the actual asynchronous completion event.
                assertTrue(sourceLost.await(4, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        assertTrue(logs.any { "configRequest" in it })
        assertTrue(logs.any { "source unavailable" in it })
    }

    @Test fun rangeChangeWaitsForTheHighLaneInsteadOfBeingLostBeforeItsConnection() {
        val address = InetAddress.getByName("127.0.0.1")
        val secret = ByteArray(32) { 7 }
        val state = AtomicReference<VehicleStatusSnapshot?>(VehicleStatusSnapshot(227, null, 53.1, null, null, null, null))
        VehicleStateRuntime(secret, VehicleStatusProvider { state.get() }, address, address, {}).use { runtime ->
            val normal = runtime.setup(VehicleStatePrototype.Lane.NORMAL.uuid, 3, "42")!!
            Peer(secret, normal.port, "42").use { normalPeer ->
                normalPeer.send("registerRequest", Opack.Array(listOf(Opack.Integer(1794))))
                assertEquals(Opack.Text("registerResponse"), normalPeer.read()["command"])
                state.set(state.get()!!.copy(rangeKm = 226))
                Thread.sleep(1200) // At least one scheduled poll with no high-priority connection.
                val high = runtime.setup(VehicleStatePrototype.Lane.HIGH.uuid, 4, "43")!!
                Peer(secret, high.port, "43").use { highPeer ->
                    val update = highPeer.read()
                    assertEquals(Opack.Text("updateNotify"), update["command"])
                    assertEquals(Opack.Dict(listOf(Opack.Integer(1794) to Opack.Integer(226))), update["values"])
                    state.set(null)
                    assertEquals(-1, highPeer.socket.getInputStream().read())
                    assertEquals(-1, normalPeer.socket.getInputStream().read())
                }
            }
        }
    }

    private class Peer(secret: ByteArray, port: Int, seed: String) : java.io.Closeable {
        val socket = Socket(InetAddress.getByName("127.0.0.1"), port).apply { soTimeout = 4000 }
        private fun key(secret: ByteArray, seed: String, direction: String) = AirPlayCrypto.hkdfSha512(
            secret, "DataStream-Salt$seed".toByteArray(), "DataStream-$direction-Encryption-Key".toByteArray())
        private val cipher = ControlCipher(key(secret, seed, "Input"), key(secret, seed, "Output"))
        private val parser = DataStreamPackage.Reader()
        fun send(command: String, values: Opack.Value) {
            val inner = Opack.dict("command" to Opack.Text(command), "transactionID" to Opack.Integer(8), "values" to values)
            val outer = Opack.encode(Opack.dict("pluginID" to Opack.Integer(0), "pluginData" to Opack.Data(Opack.encode(inner))))
            socket.getOutputStream().write(cipher.encrypt(DataStreamPackage.async(outer)))
        }
        fun read(): Opack.Dict {
            val input = DataInputStream(socket.getInputStream())
            while (true) {
                val low = input.readUnsignedByte(); val high = input.readUnsignedByte()
                val sealed = ByteArray((low or (high shl 8)) + 16); input.readFully(sealed)
                val packet = parser.feed(cipher.decrypt(byteArrayOf(low.toByte(), high.toByte()) + sealed).data)
                    .firstOrNull() ?: continue
                val outer = Opack.decode(packet.body) as Opack.Dict
                return Opack.decode((outer["pluginData"] as Opack.Data).bytes()) as Opack.Dict
            }
        }
        override fun close() = socket.close()
    }

    private fun config(on: Boolean) = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0",
        AirPlayDisplayConfig(800, 480), vehicleStateProtocol = on)
    private fun session(media: AirPlayMediaHandler) = AirPlaySession(Socket(), config(true), AirPlayIdentity.generate(),
        PairingStore(), null, object : AirPlaySessionListener {}, media)
    private fun handle(session: AirPlaySession, method: String, path: String, body: ByteArray): RtspMessage.Response =
        AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java).apply { isAccessible = true }
            .invoke(session, RtspMessage.Request(method, path, "RTSP/1.0", emptyMap(), body)) as RtspMessage.Response
}
