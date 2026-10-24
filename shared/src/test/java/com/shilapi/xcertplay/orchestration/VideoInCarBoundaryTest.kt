package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.vehicle.VehicleNavigationOutput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VideoInCarBoundaryTest {
    @Test fun blockedOldParkCannotPublishAfterReplacementAndUnknownNewSourceStaysUnavailable() {
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
        val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
        val controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            config, identity, PairingStore(), object : AirPlaySessionListener {},
            object : AirPlayMediaHandler {}, {}, vehicleNavigationOutput = VehicleNavigationOutput.NONE)
        val callbacks = controller.javaClass.getDeclaredField("sessionListener").apply { isAccessible = true }
            .get(controller) as AirPlaySessionListener
        val lifecycleLock = controller.javaClass.getDeclaredField("lifecycleLock").apply { isAccessible = true }
            .get(controller)
        val reading = AtomicReference<Boolean?>(null)
        val block = AtomicBoolean()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = mutableListOf<Pair<AirPlaySession?, Boolean>>()
        val listener = object : CarPlayVideoListener {
            override fun readParked(): Boolean? {
                val captured = reading.get()
                if (block.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
                return captured
            }
            override fun onVideoSessionStarted() {
                assertFalse(VideoInCar.allowed)
                reading.set(null)
            }
            override fun onVideoAllowedChanged(allowed: Boolean) {}
            override fun onVideoMessage(streamId: Long, message: Map<String, Any?>) {}
            override fun onVideoUiRequested() {}
            override fun onVideoSessionEnded() { reading.set(null) }
        }
        controller.videoListener = listener
        val gate = VideoInCarGate(listener::readParked,
            onChanged = { published += controller.currentAirPlaySession() to it }, dispatchLock = lifecycleLock)
        controller.javaClass.getDeclaredField("videoGate").apply { isAccessible = true }.set(controller, gate)
        val first = AirPlaySession(Socket(), config, identity, PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        val replacement = AirPlaySession(Socket(), config, identity, PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        var worker: Thread? = null
        try {
            callbacks.onSessionActive(first)
            reading.set(true)
            gate.pollOnce()
            assertTrue(VideoInCar.allowed)
            block.set(true)
            val blockedRead = Thread { gate.pollOnce() }.apply { start() }
            worker = blockedRead
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            callbacks.onSessionActive(replacement)
            assertFalse(VideoInCar.allowed)
            assertNull(reading.get())
            release.countDown()
            blockedRead.join(3_000)
            assertFalse(blockedRead.isAlive)
            assertFalse(VideoInCar.allowed)
            assertEquals(listOf(first to true, first to false), published)
            gate.pollOnce() // Current source is unknown; the old true must not be replayed.
            assertFalse(VideoInCar.allowed)
            reading.set(true)
            gate.pollOnce()
            assertEquals(replacement to true, published.last())
            callbacks.onSessionEnded(first)
            assertTrue(VideoInCar.allowed)
            callbacks.onSessionEnded(replacement)
            assertFalse(VideoInCar.allowed)
            assertEquals(replacement to false, published.last())
        } finally {
            release.countDown()
            worker?.join(3_000)
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
            first.close(); replacement.close()
            VideoInCar.allowed = false
        }
    }
}
