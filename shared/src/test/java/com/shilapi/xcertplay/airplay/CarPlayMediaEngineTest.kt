package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.Socket
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import com.shilapi.xcertplay.transport.Iap2ThemeAsset
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayMediaEngineTest {
    @Test
    fun streamConnectionIdUsesUnsignedDecimalForHkdfSalt() {
        assertEquals("18446744073709551615", unsignedPlistDecimal(-1L))
        assertEquals(
            BigInteger("18446744073709551615"),
            unsignedPlistInteger(-1L),
        )
    }

    @Test
    fun screenStreamTeardownReportsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()

        try {
            val engine = CarPlayMediaEngine(sink)
            engine.onTeardown(session, 110)
            engine.onTeardown(session, 100)
        } finally {
            session.close()
        }

        assertEquals(listOf(110 to false), events)
    }

    @Test
    fun sessionCloseReportsAllScreenStreamsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink)
        val streamsField = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val streams = streamsField.get(engine) as
            MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        streams[CarPlayMediaEngine.StreamKey(session, 110)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 111)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 100)] = Closeable {}

        engine.onSessionClosed(session)
        session.close()

        assertEquals(setOf(110 to false, 111 to false), events.toSet())
        assertTrue(streams.isEmpty())
    }

    @Test
    fun videoRemoteControlSessionsAreAcceptedOnlyWithVideoInCar() {
        val stream = mapOf("type" to 130L, "clientTypeUUID" to "A6B27562-B43A-4F2D-B75F-82391E250194", "controlType" to 1L)
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val plain = testSession()
        val video = testSession(videoInCar = true)
        try {
            assertNull(engine.onDataStream(plain, stream))
            val first = engine.onDataStream(video, stream)
            assertEquals(130, first?.get("type"))
            assertEquals(3L, first?.get("streamID"))
            assertEquals(4L, engine.onDataStream(video, stream)?.get("streamID"))
        } finally {
            plain.close()
            video.close()
        }
    }

    private class Renderer : UltraSessionRenderer {
        val events = mutableListOf<Pair<Int, Boolean>>()
        var closes = 0
        override val videoSink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) { events += type to active }
        }
        override fun panel(id: String): UiSyncPanelSession = error("No protocol requested by this test")
        override fun close() { closes++ }
    }

    private fun negotiateUltra(session: AirPlaySession, enabled: Boolean = true) {
        AirPlaySession::class.java.getDeclaredField("ultraHandshakeNegotiated").apply {
            isAccessible = true
            setBoolean(session, enabled)
        }
    }

    @Test fun developmentRendererIsOptInNegotiatedAndOwnedByOneConnection() {
        val ordinary = object : MediaSink {}
        val engine = CarPlayMediaEngine(ordinary)
        val first = testSession()
        val replacement = testSession()
        val made = mutableListOf<Renderer>()
        try {
            engine.ultraRendererFactory = { Renderer().also(made::add) }
            assertSame(ordinary, engine.screenSink(first, 110))
            assertTrue(made.isEmpty())
            negotiateUltra(first)
            val selected = engine.screenSink(first, 110)
            engine.ultraRendererFactory = null
            assertSame(selected, engine.screenSink(first, 110))
            engine.ultraRendererFactory = { Renderer().also(made::add) }
            assertSame(ordinary, engine.screenSink(first, 111))
            assertEquals(1, made.size)
            engine.onTeardown(first, 110)
            assertEquals(listOf(110 to false), made[0].events)
            engine.onSessionClosed(first)
            assertEquals(1, made[0].closes)
            negotiateUltra(replacement)
            assertNotSame(selected, engine.screenSink(replacement, 110))
            assertEquals(2, made.size)
            engine.onSessionClosed(replacement)
            assertEquals(1, made[1].closes)
            engine.onSessionClosed(first)
            assertEquals(1, made[0].closes)
        } finally { first.close(); replacement.close() }
    }

    @Test fun closedAndDisabledSessionsCannotKeepTheirDevelopmentRenderer() {
        val ordinary = object : MediaSink {}
        val engine = CarPlayMediaEngine(ordinary)
        val session = testSession()
        val renderer = Renderer()
        engine.ultraRendererFactory = { renderer }
        negotiateUltra(session)
        assertSame(renderer.videoSink, engine.screenSink(session, 110))
        negotiateUltra(session, false)
        engine.onUltraHandshakeNegotiated(session, false)
        assertEquals(1, renderer.closes)
        session.close()
        assertSame(ordinary, engine.screenSink(session, 110))
        assertEquals(1, renderer.closes)
    }

    @Test fun rendererCreatedDuringConnectionCloseIsImmediatelyRetired() {
        val ordinary = object : MediaSink {}
        val engine = CarPlayMediaEngine(ordinary)
        val session = testSession()
        val renderer = Renderer()
        negotiateUltra(session)
        engine.ultraRendererFactory = { session.close(); renderer }
        assertSame(ordinary, engine.screenSink(session, 110))
        assertEquals(1, renderer.closes)
        engine.onSessionClosed(session)
        assertEquals(1, renderer.closes)
    }

    private class ReferenceConnection : UltraReferenceConnection {
        var closes = 0
        val requests = mutableListOf<Boolean>()
        val visibility = mutableListOf<Pair<Boolean, Boolean>>()
        override val videoSink = object : MediaSink {}
        override fun panel(id: String): UiSyncPanelSession = error("No authenticated data stream in this fixture")
        override fun updateHostVisibility(resumed: Boolean, surfaceReady: Boolean) {
            visibility += resumed to surfaceReady
        }
        override fun requestAccessoryScene(request: Boolean) { requests += request }
        override fun close() { closes++ }
    }

    private fun referenceBinding() = UltraThemeBinding(Iap2ThemeAsset("44C9FA31EC", 985, "19.0"),
        UltraThemeDisplay("Center_Display", 1920, 720, 157))

    @Test fun installedReferenceHostRequiresNegotiationAndKeepsOneMainSinkUntilSessionRetirement() {
        val ordinary = object : MediaSink {}
        val engine = CarPlayMediaEngine(ordinary)
        val binding = referenceBinding()
        val made = mutableListOf<ReferenceConnection>()
        var unusedCloses = 0
        val host = UltraReferenceCenterHost(binding, { _, _ -> ReferenceConnection().also(made::add) }, { unusedCloses++ })
        val session = testSession(binding = binding)
        try {
            engine.installReferenceCenterHost(host)
            assertSame(ordinary, engine.screenSink(session, 110))
            assertTrue(made.isEmpty())
            negotiateUltra(session)
            val selected = engine.screenSink(session, 110)
            assertSame(made.single().videoSink, selected)
            assertSame(selected, engine.screenSink(session, 110))
            val alternate = engine.screenSink(session, 111)
            assertNotSame(ordinary, alternate)
            assertNotSame(selected, alternate)
            assertEquals(1, made.size)
            assertEquals(listOf(true), made.single().requests)
            assertEquals(false to false, made.single().visibility.last())
            engine.onSessionClosed(session)
            engine.onSessionClosed(session)
            assertEquals(1, made.single().closes)
            assertEquals(0, unusedCloses)
        } finally {
            session.close()
            host.close()
        }
        assertEquals(1, unusedCloses)
    }

    @Test fun installedReferenceHostRejectsWrongBindingWithoutCreatingAnOwnerAndLatchesTheFailure() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val binding = referenceBinding()
        var creations = 0
        var failures = 0
        val host = UltraReferenceCenterHost(binding, { _, _ -> creations++; ReferenceConnection() }, {})
        val attachment = host.attachHost { failures++ }
        val mismatch = testSession(binding = binding.copy(asset = binding.asset.copy(version = 986)))
        val matching = testSession(binding = binding)
        try {
            engine.installReferenceCenterHost(host)
            negotiateUltra(mismatch)
            assertThrows(IllegalStateException::class.java) { engine.screenSink(mismatch, 110) }
            assertEquals(1, failures)
            assertEquals(0, creations)
            // Even a later exact match cannot revive the failed host or create a replacement owner.
            negotiateUltra(matching)
            assertThrows(IllegalStateException::class.java) { engine.screenSink(matching, 110) }
            assertEquals(0, creations)
            assertEquals(1, failures)
        } finally {
            engine.onSessionClosed(mismatch); engine.onSessionClosed(matching)
            mismatch.close(); matching.close(); attachment.close(); host.close()
        }
    }

    @Test fun declinedReferenceNegotiationReportsFailureAndClosesTheSessionBeforeAnyRendererExists() {
        val ordinary = object : MediaSink {}
        val engine = CarPlayMediaEngine(ordinary)
        val binding = referenceBinding()
        var creations = 0
        var failures = 0
        val host = UltraReferenceCenterHost(binding, { _, _ -> creations++; ReferenceConnection() }, {})
        val attachment = host.attachHost { failures++ }
        val session = testSession(binding = binding)
        try {
            engine.installReferenceCenterHost(host)
            assertFalse(session.isClosed)
            engine.onUltraHandshakeNegotiated(session, false)
            assertTrue(session.isClosed)
            assertEquals(1, failures)
            assertEquals(0, creations)
            assertSame(ordinary, engine.screenSink(session, 110))
            engine.onUltraHandshakeNegotiated(session, false)
            assertEquals(1, failures)
        } finally { session.close(); attachment.close(); host.close() }
    }

    private fun testSession(videoInCar: Boolean = false, binding: UltraThemeBinding? = null): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = binding?.main?.config(30) ?: AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            videoInCar = videoInCar,
            ultraHandshake = binding != null,
            ultraThemeBinding = binding,
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
    )
}
