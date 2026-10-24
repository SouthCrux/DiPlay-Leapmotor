package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UltraHandshakeTest {
    private fun config(on: Boolean = true) = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0",
        AirPlayDisplayConfig(800, 480), ultraHandshake = on)

    @Test fun probeIsOptInAndKeepsPluginsOffRealVideoStreams() {
        val off = AirPlayInfoPlist.build(config(false))
        assertFalse(off.containsKey("uiSyncInfo")); assertFalse(off.containsKey("displayPanels"))
        val cfg = config().copy(cluster = AirPlayDisplayConfig(720, 720))
        val info = AirPlayInfoPlist.build(cfg)
        assertEquals(AirPlayInfoPlist.build(cfg.copy(ultraHandshake = false))["displays"], info["displays"])
        assertFalse(info.containsKey("vehicleStateProtocolInfo"))
        val panels = (info["displayPanels"] as List<*>).map { it as Map<*, *> }
        assertEquals(3, panels.size)
        assertEquals(UltraHandshake.panelIds(cfg) - UltraHandshake.PROBE_PLUGIN, panels.map { it["uid"] }.toSet())
        panels.take(2).forEach {
            assertFalse(it.containsKey("displayPlugins"))
            assertEquals(1, (it["initialVideoStreams"] as List<*>).size)
            assertTrue((it["widthPhysical"] as Number).toInt() > 0)
        }
        assertEquals(emptyList<Any>(), panels.last()["initialVideoStreams"])
        val plugin = (panels.last()["displayPlugins"] as List<*>).single() as Map<*, *>
        assertEquals(emptyList<Any>(), plugin["videoStreams"])
    }

    @Test fun selectionRequiresProposalButNotVehicleData() {
        val offered = listOf("uiSync", "vehicleStateProtocol", "fileTransfer")
        assertFalse("uiSync" in setupEnabledFeatures(config(false), offered))
        assertFalse("uiSync" in setupEnabledFeatures(config(), null))
        val selected = setupEnabledFeatures(config(), offered)
        assertTrue("uiSync" in selected)
        assertFalse("vehicleStateProtocol" in selected)
        assertFalse("fileTransfer" in selected)
        val vehicleServices = config().copy(vehicleStateProtocol = true)
        assertTrue("vehicleStateProtocol" in setupEnabledFeatures(vehicleServices, offered))
        assertTrue(AirPlayInfoPlist.build(vehicleServices).containsKey("vehicleStateProtocolInfo"))
    }

    @Test fun vehicleServicesAndUiSyncCanCoexistOnlyWhenTheMediaOwnerCanServeThem() {
        val cfg = config().copy(vehicleStateProtocol = true)
        val snapshot = com.shilapi.xcertplay.transport.VehicleStatusSnapshot(220, null, 61.0, null, null, null, null)
        for (available in listOf(false, true)) {
            val media = CarPlayMediaEngine(object : MediaSink {},
                vehicleStateProvider = com.shilapi.xcertplay.transport.VehicleStatusProvider { snapshot.takeIf { available } })
            session(media, cfg).use { session ->
                val info = BplistCodec.decode(handle(session, "GET", "/info").body) as Map<*, *>
                assertTrue(info.containsKey("uiSyncInfo"))
                assertEquals(available, info.containsKey("vehicleStateProtocolInfo"))
                val reply = handle(session, "SETUP", "/", mapOf("features" to listOf("uiSync", "vehicleStateProtocol")))
                val features = (BplistCodec.decode(reply.body) as Map<*, *>)["enabledFeatures"] as List<*>
                assertTrue("uiSync" in features)
                assertEquals(available, "vehicleStateProtocol" in features)
            }
        }
    }

    @Test fun sessionOnlyAdvertisesHandshakeWhenMediaLayerCanServeIt() {
        for (available in listOf(false, true)) {
            val media = object : AirPlayMediaHandler { override val ultraHandshakeAvailable = available }
            session(media).use { session ->
                val info = BplistCodec.decode(handle(session, "GET", "/info").body) as Map<*, *>
                assertEquals(available, info.containsKey("uiSyncInfo"))
                assertEquals(available, info.containsKey("displayPanels"))
                assertFalse(info.containsKey("vehicleStateProtocolInfo"))
                val setup = handle(session, "SETUP", "/", mapOf("features" to listOf("uiSync")))
                val selected = (BplistCodec.decode(setup.body) as Map<*, *>)["enabledFeatures"] as List<*>
                assertEquals(available, "uiSync" in selected)
                assertEquals(available, session.ultraHandshakeNegotiated)
                assertFalse(session.vehicleStateNegotiated)
            }
        }
    }

    @Test fun mediaRejectsUiSyncBeforePairVerificationEvenWhenSelected() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        session(engine).use { session ->
            handle(session, "SETUP", "/", mapOf("features" to listOf("uiSync")))
            assertTrue(session.ultraHandshakeNegotiated)
            assertNull(engine.onDataStream(session, mapOf("clientTypeUUID" to UltraHandshake.DATASTREAM_UUID, "seed" to 42L)))
        }
    }

    @Test fun encryptedResetCompletesWithoutVehicleSourceOrRenderer() {
        val loop = InetAddress.getByName("127.0.0.1")
        val secret = ByteArray(32) { 7 }
        val logs = CopyOnWriteArrayList<String>()
        UltraHandshakeRuntime(secret, loop, loop, setOf(UltraHandshake.PROBE_PANEL), logs::add).use { runtime ->
            val bound = runtime.setup(3, "42")!!
            Peer(secret, bound.port, "42").use { peer ->
                // Unknown panel frames must not affect the advertised panel's reset sequence.
                peer.send(reset(), "not-advertised")
                peer.send(reset())
                val reset = peer.read()
                assertEquals(0L, reset["type"])
                assertEquals(2L, reset["version"])
                assertEquals("fixture", reset["id"])
                peer.send(mapOf("type" to 1L, "ssn" to 1L, "psn" to 1L, "version" to 3L))
                assertEquals(1L, peer.read()["type"])
                peer.send(mapOf("type" to 2L, "ssn" to 1L, "psn" to 2L,
                    "data" to BplistCodec.encode(mapOf("type" to 3L,
                        "data" to mapOf("layoutID" to "private-layout")))))
                peer.send(reset())
                // The layout remains unanswered; the next packet is the new reset reply.
                assertEquals(0L, peer.read()["type"])
                assertTrue(logs.any { "type=2 command=3" in it && "replies=0" in it })
                assertFalse(logs.any { "private-layout" in it })
                assertNull(runtime.setup(4, "42")) // Never reset cipher counters with reused keys.
                runtime.teardown(1) // The iAP identity does not own this endpoint.
                runtime.teardown(99)
                runtime.teardown(3)
                assertEquals(-1, peer.socket.getInputStream().read()) // No queued layout/focus commands.
            }
        }
        assertTrue(logs.any { "unadvertised panel rejected" in it })
        assertEquals(1, logs.count { "UI Sync reset complete" in it })
        assertTrue(logs.any { "telemetry=false" in it })
    }

    @Test fun replacementRetiresOldChannelAndStaleTeardownCannotCloseNewOne() {
        val loop = InetAddress.getByName("127.0.0.1")
        val secret = ByteArray(32) { 7 }
        UltraHandshakeRuntime(secret, loop, loop, setOf(UltraHandshake.PROBE_PANEL), {}).use { runtime ->
            val first = runtime.setup(3, "42")!!
            Peer(secret, first.port, "42").use { old ->
                old.send(reset()); assertEquals(0L, old.read()["type"])
                val second = runtime.setup(4, "43")!!
                assertEquals(-1, old.socket.getInputStream().read())
                runtime.teardown(3)
                assertNull(runtime.setup(3, "44"))
                Peer(secret, second.port, "43").use { current ->
                    current.send(reset()); assertEquals(0L, current.read()["type"])
                    runtime.close()
                    assertEquals(-1, current.socket.getInputStream().read())
                }
                assertNull(runtime.setup(5, "45"))
            }
        }
    }

    @Test fun explicitOwnerReplacesKotlinProtocolAndIsRetiredWhenStreamChanges() {
        val loop = InetAddress.getByName("127.0.0.1")
        val secret = ByteArray(32) { 7 }
        val owners = CopyOnWriteArrayList<UiSyncPanelSession>()
        val closed = java.util.concurrent.atomic.AtomicInteger()
        val factory: (String) -> UiSyncPanelSession = { panel ->
            assertEquals(UltraHandshake.PROBE_PANEL, panel)
            object : UiSyncPanelSession {
                override fun receive(bytes: ByteArray): List<ByteArray> {
                    assertEquals(0L, BoundedPlist.decode(bytes)?.get("type"))
                    return listOf(BplistCodec.encode(mapOf("type" to 99L, "psn" to 700L)))
                }
                override fun close() { closed.incrementAndGet() }
            }.also(owners::add)
        }
        UltraHandshakeRuntime(secret, loop, loop, setOf(UltraHandshake.PROBE_PANEL), {}, factory).use { runtime ->
            val first = runtime.setup(3, "42")!!
            Peer(secret, first.port, "42").use { peer ->
                peer.send(reset())
                // A Kotlin reset owner would instead reply type=0, psn=1.
                val reply = peer.read()
                assertEquals(99L, reply["type"])
                assertEquals(700L, reply["psn"])
                val second = runtime.setup(4, "43")!!
                assertEquals(1, closed.get())
                assertEquals(-1, peer.socket.getInputStream().read())
                runtime.teardown(3)
                Peer(secret, second.port, "43").use { replacement ->
                    replacement.send(reset())
                    assertEquals(99L, replacement.read()["type"])
                }
            }
        }
        assertEquals(2, owners.size)
        assertEquals(2, closed.get())
    }

    private fun reset() = mapOf("type" to 0L, "ssn" to 1L, "psn" to 1L, "asn" to 0L, "id" to "fixture", "version" to 3L)
    private class Peer(private val secret: ByteArray, port: Int, private val seed: String) : Closeable {
        val socket = Socket(InetAddress.getByName("127.0.0.1"), port).apply { soTimeout = 4000 }
        private fun key(direction: String) = AirPlayCrypto.hkdfSha512(secret, "DataStream-Salt$seed".toByteArray(),
            "DataStream-$direction-Encryption-Key".toByteArray())
        private val cipher = ControlCipher(key("Input"), key("Output"))
        private val parser = DataStreamPackage.Reader()
        fun send(header: Map<String, Any>, panel: String = UltraHandshake.PROBE_PANEL) {
            val body = Opack.encode(Opack.dict("displayUUID" to Opack.Text(panel), "data" to Opack.Data(BplistCodec.encode(header))))
            socket.getOutputStream().write(cipher.encrypt(DataStreamPackage.async(body)))
        }
        fun read(): Map<String, Any?> {
            val input = DataInputStream(socket.getInputStream())
            while (true) {
                val low = input.readUnsignedByte(); val high = input.readUnsignedByte()
                val sealed = ByteArray((low or (high shl 8)) + 16); input.readFully(sealed)
                val packet = parser.feed(cipher.decrypt(byteArrayOf(low.toByte(), high.toByte()) + sealed).data).firstOrNull() ?: continue
                val frame = Opack.decode(packet.body) as Opack.Dict
                return BoundedPlist.decode((frame["data"] as Opack.Data).bytes())!!
            }
        }
        override fun close() = socket.close()
    }

    private fun session(media: AirPlayMediaHandler, cfg: AirPlayConfig = config()) = AirPlaySession(Socket(), cfg, AirPlayIdentity.generate(),
        PairingStore(), null, object : AirPlaySessionListener {}, media)
    private fun handle(session: AirPlaySession, method: String, path: String, fields: Map<String, Any>? = null): RtspMessage.Response =
        AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java).apply { isAccessible = true }
            .invoke(session, RtspMessage.Request(method, path, "RTSP/1.0", emptyMap(), fields?.let(BplistCodec::encode) ?: byteArrayOf())) as RtspMessage.Response
}
