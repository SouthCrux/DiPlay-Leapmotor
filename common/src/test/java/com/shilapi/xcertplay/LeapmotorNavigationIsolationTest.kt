package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.CarPlayTurnOverlay
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.vehicle.VehicleIntegrationPolicy
import com.shilapi.xcertplay.vehicle.VehicleNavigationOutput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class LeapmotorNavigationIsolationTest {
    @Test fun c11ControllerDecodesAndClearsItsTurnCardWithoutStartingOemOutputs() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_META_DATA)
        info.applicationInfo!!.metaData = Bundle().apply {
            putString(VehicleIntegrationPolicy.METADATA_KEY, VehicleIntegrationPolicy.LEAPMOTOR_C11)
        }
        shadowOf(app.packageManager).installPackage(info)
        val context = object : ContextWrapper(app) {
            var binds = 0
            var broadcasts = 0
            override fun getApplicationContext(): Context = this
            override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
                binds++
                return false
            }
            override fun sendBroadcast(intent: Intent) { broadcasts++ }
        }
        val events = mutableListOf<ClusterTurnGuidance?>()
        CarPlayTurnOverlay.endNow()
        CarPlayTurnOverlay.setListener { events += it }
        BydNavigationOutputs.onAppOpened(context)
        var telemetryClosed = false
        val telemetry = object : com.shilapi.xcertplay.transport.ManagedVehicleStatusProvider {
            override fun snapshot(): com.shilapi.xcertplay.transport.VehicleStatusSnapshot? = null
            override fun close() { telemetryClosed = true }
        }
        val controller = CarPlayController(
            context, CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRED,
                identification = Iap2IdentificationConfig(
                    name = "test", modelIdentifier = "test", manufacturer = "test",
                    serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1",
                    carPlayUsbInterfaceNumber = 3,
                ),
            ), AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {},
            vehicleStatusProvider = telemetry,
        )
        try {
            val output = controller.javaClass.getDeclaredField("vehicleNavigationOutput").apply { isAccessible = true }
            assertSame(VehicleNavigationOutput.NONE, output.get(controller))
            val accept = controller.javaClass.getDeclaredMethod("onRouteFrame", Iap2Frame::class.java)
                .apply { isAccessible = true }
            accept.invoke(controller, Iap2Frame(0x5202, tlv(1, 0, 7) + tlv(3, 1)))
            accept.invoke(controller, Iap2Frame(0x5201,
                tlv(1, 1) + tlv(0x0a, 0, 0, 0, 150) + tlv(0x0d, 0, 7)))
            assertEquals(150, events.last()!!.distanceMeters)
            assertEquals(2, events.last()!!.icon)
            assertEquals(0, context.binds)
            assertEquals(0, context.broadcasts)
        } finally {
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
            assertTrue(telemetryClosed)
            assertNull(events.last())
            CarPlayTurnOverlay.setListener(null)
        }
    }

    private fun tlv(type: Int, vararg bytes: Int): ByteArray =
        byteArrayOf(0, (bytes.size + 4).toByte(), (type ushr 8).toByte(), type.toByte()) +
            bytes.map(Int::toByte).toByteArray()
}
