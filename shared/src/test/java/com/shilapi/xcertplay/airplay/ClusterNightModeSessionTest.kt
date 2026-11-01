package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterNightModeSessionTest {
    private class EventSocket : Socket() {
        val bytes = ByteArrayOutputStream()
        override fun getOutputStream() = bytes
    }

    private fun session(cluster: Boolean = true) = AirPlaySession(Socket(),
        AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "366.0",
            main = AirPlayDisplayConfig(1280, 720),
            cluster = if (cluster) CarPlayClusterDisplay.config(720, 720) else null),
        AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {},
        object : AirPlayMediaHandler {
            override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int = 12345
        })

    private fun eventReady(session: AirPlaySession): EventSocket = EventSocket().also { socket ->
        for ((key, value) in listOf("eventSocket" to socket, "eventCipher" to ControlCipher(ByteArray(32), ByteArray(32))))
            session.javaClass.getDeclaredField(key).apply { isAccessible = true }.set(session, value)
    }

    private fun call(session: AirPlaySession, name: String) {
        session.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(session)
    }

    private fun setup(session: AirPlaySession) {
        session.javaClass.getDeclaredMethod("handleStreams", List::class.java).apply { isAccessible = true }
            .invoke(session, listOf(mapOf("type" to 111L)))
    }

    private fun commands(socket: EventSocket): List<Map<*, *>> {
        if (socket.bytes.size() == 0) return emptyList()
        val plain = ControlCipher(ByteArray(32), ByteArray(32)).decrypt(socket.bytes.toByteArray()).data
        return RtspMessage.parseMessages(plain).messages.map { BplistCodec.decode(it.body) as Map<*, *> }
            .filter { it["type"] == "setNightMode" }.map { it["params"] as Map<*, *> }
    }

    @Test fun lateClusterReceivesCurrentDayModeAfterSetupWithoutReconnectingMain() {
        val session = session()
        try {
            val event = eventReady(session)
            assertTrue(session.setNightMode(false))
            assertEquals(listOf(mapOf("nightMode" to false)), commands(event))
            setup(session)
            assertEquals("No command before the cluster SETUP response", 1, commands(event).size)
            call(session, "notifySetupResponseSent")
            assertEquals(listOf(mapOf("nightMode" to false),
                mapOf("uuid" to AirPlayInfoPlist.ALT_UUID, "nightMode" to false)), commands(event))
            call(session, "notifySetupResponseSent")
            assertEquals("Unrelated later SETUP does not duplicate the appearance update", 2, commands(event).size)
        } finally { session.close() }
    }

    @Test fun queuedLatestModeAndLiveDayNightChangesReachBothDisplays() {
        val session = session()
        try {
            assertFalse(session.setNightMode(true))
            assertFalse(session.setNightMode(false))
            setup(session)
            val event = eventReady(session)
            call(session, "notifySetupResponseSent")
            assertEquals(listOf(false, false), commands(event).map { it["nightMode"] })
            assertTrue(session.setNightMode(true))
            assertTrue(session.setNightMode(false))
            assertEquals(listOf(false, false, true, true, false, false), commands(event).map { it["nightMode"] })
            assertEquals(listOf(null, AirPlayInfoPlist.ALT_UUID, null, AirPlayInfoPlist.ALT_UUID,
                null, AirPlayInfoPlist.ALT_UUID), commands(event).map { it["uuid"] })
            session.close()
            assertFalse(session.setNightMode(true))
        } finally { session.close() }
    }

    @Test fun recreatedClusterReappliesNightModeAndMainOnlySessionKeepsLegacyCommand() {
        val session = session()
        try {
            val event = eventReady(session)
            setup(session)
            assertTrue(session.setNightMode(true))
            session.javaClass.getDeclaredMethod("clearClusterContent").apply { isAccessible = true }.invoke(session)
            setup(session)
            call(session, "notifySetupResponseSent")
            assertEquals(listOf(null, AirPlayInfoPlist.ALT_UUID, AirPlayInfoPlist.ALT_UUID), commands(event).map { it["uuid"] })
            assertTrue(commands(event).all { it["nightMode"] == true })
        } finally { session.close() }
        val mainOnly = session(cluster = false)
        try {
            val event = eventReady(mainOnly)
            assertTrue(mainOnly.setNightMode(false))
            assertEquals(listOf(mapOf("nightMode" to false)), commands(event))
        } finally { mainOnly.close() }
    }
}
