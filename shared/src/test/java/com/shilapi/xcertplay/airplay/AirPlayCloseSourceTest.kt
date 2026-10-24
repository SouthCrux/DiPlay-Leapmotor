package com.shilapi.xcertplay.airplay

import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AirPlayCloseSourceTest {
    private fun session(socket: Socket, listener: AirPlaySessionListener,
                        media: AirPlayMediaHandler = object : AirPlayMediaHandler {}) = AirPlaySession(
        socket, AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0",
            main = AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity.generate(), PairingStore(), null, listener, media,
    )

    @Test fun firstCloseSourceSurvivesErrorsCausedByItsOwnSocketTeardown() {
        val logs = mutableListOf<String>()
        val ended = AtomicInteger()
        val socket = Socket()
        val session = session(socket, object : AirPlaySessionListener {
            override fun onDebugLog(message: String) { logs += message }
            override fun onSessionEnded(session: AirPlaySession) { ended.incrementAndGet() }
        })
        session.closeFrom(AirPlayCloseSource.SCREEN_CLOSED)
        session.closeFrom(AirPlayCloseSource.CONTROL_IO)
        session.close()
        assertEquals(listOf("airplay session closing source=SCREEN_CLOSED"),
            logs.filter { it.startsWith("airplay session closing source=") })
        assertEquals(1, ended.get())
        assertTrue(socket.isClosed)
    }

    @Test fun actualControlPeerEofIsRecordedBeforeTeardown() {
        val ended = CountDownLatch(1)
        val logs = Collections.synchronizedList(mutableListOf<String>())
        ServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use { peer ->
                val session = session(server.accept(), object : AirPlaySessionListener {
                    override fun onDebugLog(message: String) { logs += message }
                    override fun onSessionEnded(session: AirPlaySession) { ended.countDown() }
                })
                try {
                    session.start()
                    peer.shutdownOutput()
                    assertTrue("control EOF did not retire session", ended.await(3, TimeUnit.SECONDS))
                    assertTrue(session.isClosed)
                    assertTrue(logs.contains("airplay session closing source=CONTROL_EOF"))
                } finally { session.close() }
            }
        }
    }

    @Test fun openingAnUnconnectedControlSocketStillRetiresTheSession() {
        val ended = CountDownLatch(1)
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val session = session(Socket(), object : AirPlaySessionListener {
            override fun onDebugLog(message: String) { logs += message }
            override fun onSessionEnded(session: AirPlaySession) { ended.countDown() }
        })
        try {
            session.start()
            assertTrue("stream acquisition failure escaped cleanup", ended.await(3, TimeUnit.SECONDS))
            assertTrue(session.isClosed)
            assertTrue(logs.contains("airplay session closing source=CONTROL_IO"))
        } finally { session.close() }
    }

}
