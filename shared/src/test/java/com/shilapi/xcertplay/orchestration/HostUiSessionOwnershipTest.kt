package com.shilapi.xcertplay.orchestration

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.vehicle.VehicleNavigationOutput
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class HostUiSessionOwnershipTest {
    private class HomeContext(base: Context) : ContextWrapper(base) {
        val launches = mutableListOf<Intent>()
        override fun getApplicationContext(): Context = this
        override fun startActivity(intent: Intent) { launches += Intent(intent) }
    }
    private class Fixture : AutoCloseable {
        val context = HomeContext(RuntimeEnvironment.getApplication())
        val hostRequests = mutableListOf<AirPlaySession>()
        val commands = mutableListOf<Pair<AirPlaySession, String>>()
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
        val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
        val controller = CarPlayController(context,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
                identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test",
                    manufacturer = "test", serialNumber = "test", firmwareVersion = "1",
                    hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            config, identity, PairingStore(), object : AirPlaySessionListener {
                override fun onHostUiRequested(session: AirPlaySession) { hostRequests += session }
                override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
                    commands += session to type
                }
            }, object : AirPlayMediaHandler {}, {}, vehicleNavigationOutput = VehicleNavigationOutput.NONE)
        val listener = field("sessionListener") as AirPlaySessionListener
        val lock = field("lifecycleLock")
        val handoff = field("wirelessHandoffRequested") as AtomicBoolean
        val sessions = mutableListOf<AirPlaySession>()
        fun session() = AirPlaySession(Socket(), config, identity, PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}).also(sessions::add)
        private fun field(name: String): Any = controller.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(controller)
        override fun close() {
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
            sessions.forEach { it.close() }
        }
    }

    @Test fun onlyCurrentRecordedSessionCanOpenHomeOrForwardCommands() = Fixture().use { f ->
        val first = f.session()
        val replacement = f.session()
        f.listener.onHostUiRequested(first)
        f.listener.onCommand(first, "requestUI", emptyMap())
        assertTrue(f.context.launches.isEmpty())
        assertTrue(f.commands.isEmpty())
        f.listener.onSessionActive(first)
        f.listener.onHostUiRequested(first)
        assertEquals(listOf(first), f.hostRequests)
        val home = f.context.launches.single()
        assertEquals(Intent.ACTION_MAIN, home.action)
        assertTrue(home.hasCategory(Intent.CATEGORY_HOME))
        assertTrue(home.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        f.listener.onSessionActive(replacement)
        f.listener.onHostUiRequested(first)
        f.listener.onCommand(first, "requestUI", emptyMap())
        assertEquals(1, f.context.launches.size)
        assertTrue(f.commands.isEmpty())
        f.listener.onSessionEnded(first)
        f.listener.onHostUiRequested(replacement)
        f.listener.onCommand(replacement, "requestUI", emptyMap())
        assertEquals(listOf(first, replacement), f.hostRequests)
        assertEquals(listOf(replacement to "requestUI"), f.commands)
    }

    @Test fun closedSessionAndClosedControllerCannotLaunchHomeOrDeliverCommands() = Fixture().use { f ->
        val session = f.session()
        f.listener.onSessionActive(session)
        session.close()
        f.listener.onHostUiRequested(session)
        f.listener.onCommand(session, "requestUI", emptyMap())
        val live = f.session()
        f.listener.onSessionActive(live)
        f.controller.close()
        f.listener.onHostUiRequested(live)
        f.listener.onCommand(live, "requestUI", emptyMap())
        assertTrue(f.context.launches.isEmpty())
        assertTrue(f.hostRequests.isEmpty())
        assertTrue(f.commands.isEmpty())
    }

    @Test fun activeWirelessHandoffStillWorksButRetiredAndPreRecordCommandsCannotArmIt() = Fixture().use { f ->
        val first = f.session()
        f.listener.onCommand(first, "disableBluetooth", emptyMap())
        assertFalse(f.handoff.get())
        f.listener.onSessionActive(first)
        f.listener.onCommand(first, "disableBluetooth", emptyMap())
        assertTrue(f.handoff.get())
        f.handoff.set(false)
        val replacement = f.session()
        f.listener.onSessionActive(replacement)
        f.listener.onCommand(first, "disableBluetooth", emptyMap())
        assertFalse(f.handoff.get())
        f.listener.onCommand(replacement, "disable-bluetooth", emptyMap())
        assertTrue(f.handoff.get())
        assertEquals(listOf(first to "disableBluetooth", replacement to "disable-bluetooth"), f.commands)
    }

    @Test fun callbackQueuedBeforeReplacementRechecksOwnershipBeforeOpeningHome() = Fixture().use { f ->
        val first = f.session()
        val replacement = f.session()
        f.listener.onSessionActive(first)
        val entered = CountDownLatch(1)
        lateinit var callback: Thread
        synchronized(f.lock) {
            callback = Thread {
                entered.countDown()
                f.listener.onHostUiRequested(first)
                f.listener.onCommand(first, "requestUI", emptyMap())
            }.apply { start() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            f.listener.onSessionActive(replacement)
        }
        callback.join(2_000)
        assertFalse(callback.isAlive)
        assertTrue(f.context.launches.isEmpty())
        assertTrue(f.hostRequests.isEmpty())
        assertTrue(f.commands.isEmpty())
    }
}
