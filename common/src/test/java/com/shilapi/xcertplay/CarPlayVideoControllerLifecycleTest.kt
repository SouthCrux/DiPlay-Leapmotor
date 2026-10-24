package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.vehicle.VehicleNavigationOutput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.Closeable
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayVideoControllerLifecycleTest {
    private class Fixture : Closeable {
        private val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
        private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
        val controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            config, identity, PairingStore(), object : AirPlaySessionListener {},
            object : AirPlayMediaHandler {}, {}, vehicleNavigationOutput = VehicleNavigationOutput.NONE)
        val callbacks = controller.javaClass.getDeclaredField("sessionListener").apply { isAccessible = true }
            .get(controller) as AirPlaySessionListener
        private val sessions = mutableListOf<AirPlaySession>()
        fun phone(): AirPlaySession = AirPlaySession(Socket(), config, identity, PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}).also(sessions::add)
        fun active(): Any? = controller.javaClass.getDeclaredField("activeSession")
            .apply { isAccessible = true }.get(controller)
        override fun close() {
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
            sessions.forEach { it.close() }
        }
    }

    @Test fun activeAssignmentPrecedesStartedAndRetiredSessionCannotEndItsReplacement() {
        Fixture().use { f ->
            val first = f.phone()
            val replacement = f.phone()
            val events = mutableListOf<String>()
            val expected = mutableListOf<AirPlaySession>()
            f.controller.videoListener = object : CarPlayVideoListener {
                override fun onVideoSessionStarted() {
                    assertSame(expected.removeAt(0), f.active())
                    events += "started"
                }
                override fun readParked(): Boolean? = null
                override fun onVideoAllowedChanged(allowed: Boolean) {}
                override fun onVideoMessage(streamId: Long, message: Map<String, Any?>) { events += "message" }
                override fun onVideoUiRequested() { events += "ui" }
                override fun onVideoSessionEnded() {
                    assertNull(f.active())
                    events += "ended"
                }
            }
            expected += first
            f.callbacks.onSessionActive(first)
            f.callbacks.onSessionActive(first) // Duplicate activation is not another session.
            expected += replacement
            f.callbacks.onSessionActive(replacement)
            f.callbacks.onSessionEnded(first)
            f.callbacks.onRemoteControlMessage(first, 1, emptyMap())
            f.callbacks.onVideoPlaybackUiRequested(first)
            assertSame(replacement, f.active())
            assertEquals(listOf("started", "started"), events)
            f.callbacks.onRemoteControlMessage(replacement, 1, emptyMap())
            f.callbacks.onVideoPlaybackUiRequested(replacement)
            f.callbacks.onSessionEnded(replacement)
            assertEquals(listOf("started", "started", "message", "ui", "ended"), events)
            assertTrue(expected.isEmpty())
        }
    }

    @Test fun controllerCloseRetiresVideoBindingEvenIfNoAirPlaySessionEverBecameActive() {
        Fixture().use { f ->
            var closed = 0
            f.controller.videoListener = object : CarPlayVideoListener, Closeable {
                override fun readParked(): Boolean? = null
                override fun onVideoAllowedChanged(allowed: Boolean) {}
                override fun onVideoMessage(streamId: Long, message: Map<String, Any?>) {}
                override fun onVideoUiRequested() {}
                override fun onVideoSessionEnded() {}
                override fun close() { closed++ }
            }
            f.controller.close()
            f.controller.close()
            assertEquals(1, closed)
        }
    }

    @Test fun liveSessionAccessorWorksWithoutUiReplayAndRejectsEndedClosedOrRetiredSessions() {
        Fixture().use { f ->
            assertNull(f.controller.currentAirPlaySession())
            val first = f.phone()
            f.callbacks.onSessionActive(first)
            assertSame(first, f.controller.currentAirPlaySession())
            var replayed = 0
            f.controller.attachUi(object : AirPlaySessionListener {
                override fun onSessionActive(session: AirPlaySession) { replayed++ }
            }, {})
            assertEquals(0, replayed)
            first.close()
            assertNull(f.controller.currentAirPlaySession())
            val replacement = f.phone()
            f.callbacks.onSessionActive(replacement)
            f.callbacks.onSessionEnded(first)
            assertSame(replacement, f.controller.currentAirPlaySession())
            f.callbacks.onSessionEnded(replacement)
            assertNull(f.controller.currentAirPlaySession())
            val last = f.phone()
            f.callbacks.onSessionActive(last)
            f.controller.close()
            assertNull(f.controller.currentAirPlaySession())
        }
    }
}
