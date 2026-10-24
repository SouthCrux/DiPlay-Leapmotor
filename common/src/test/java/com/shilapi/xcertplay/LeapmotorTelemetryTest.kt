package com.shilapi.xcertplay

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import com.shilapi.xcertplay.vehicle.LeapmotorTelemetry
import com.shilapi.xcertplay.vehicle.VehicleIntegrationPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [32])
class LeapmotorTelemetryTest {
    private val app = RuntimeEnvironment.getApplication()
    private val component = ComponentName(app, "telemetry")
    private class FakeContext(base: Context) : ContextWrapper(base) {
        val connections = mutableListOf<ServiceConnection>()
        var unbinds = 0
        override fun getApplicationContext(): Context = this
        override fun checkSelfPermission(permission: String): Int =
            if (permission == com.shilapi.xcertplay.vehicle.LeapmotorExteriorTemperatureIntegration.PERMISSION)
                PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            connections += connection
            return true
        }
        override fun unbindService(connection: ServiceConnection) { unbinds++ }
    }
    private fun profile(value: String) {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_META_DATA)
        info.applicationInfo!!.metaData = Bundle().apply { putString(VehicleIntegrationPolicy.METADATA_KEY, value) }
        shadowOf(app.packageManager).installPackage(info)
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun attach(connection: ServiceConnection): Messenger {
        var client: Messenger? = null
        val server = Messenger(Handler(Looper.getMainLooper()) { message -> client = message.replyTo; true })
        connection.onServiceConnected(component, server.binder)
        idle()
        return client!!
    }
    private fun deliver(client: Messenger) {
        fun send(service: Int, instance: Int, event: Int, payload: ByteArray) {
            client.send(Message.obtain(null, 2).apply { data = Bundle().apply {
                putInt("service", service); putInt("instance", instance); putInt("event", event)
                putByteArray("payload", payload); putLong("receivedAt", SystemClock.elapsedRealtime())
            } })
        }
        send(39168, 39169, 6000, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(53.1).array())
        send(4161, 20545, 301, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(227).array())
        idle()
    }
    private fun climate(client: Messenger, event: Int, value: Double?, at: Long = SystemClock.elapsedRealtime()) {
        client.send(Message.obtain(null, 2).apply { data = Bundle().apply {
            putInt("service", 39168); putInt("instance", 39169); putInt("event", event)
            putInt("group", 39171)
            putByteArray("payload", value?.let {
                ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(it).array()
            })
            putLong("receivedAt", at)
        } })
        idle()
    }
    private fun oemInteger(client: Messenger, event: Int, raw: Int) {
        client.send(Message.obtain(null, 2).apply { data = Bundle().apply {
            putInt("service", 39168); putInt("instance", 39169); putInt("group", 39171)
            putInt("event", event)
            putByteArray("payload", ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw).array())
            putLong("receivedAt", SystemClock.elapsedRealtime())
        } })
        idle()
    }

    private fun logs(tag: String) = ShadowLog.getLogsForTag(tag).map { it.msg }
    private fun advanceLogWindow(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    @Test fun wiperNoticesAreReadOnlyAndDoNotPretendToReportWipingSpeed() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            for (event in 1521..1526) oemInteger(client, event, 1)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("module=WIPERS channel=FRONT_WIPER_START_FAILURE status=OBSERVED_UNVERIFIED raw=1 "))
            assertTrue(report.contains("module=WIPERS channel=REAR_WIPER_START_FAILURE status=OBSERVED_UNVERIFIED raw=1 "))
            climate(client, 1523, 1.0)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=FRONT_WIPER_START_FAILURE status=UNAVAILABLE raw=UNKNOWN"))
            assertTrue(report.contains("channel=REAR_WIPER_START_FAILURE status=OBSERVED_UNVERIFIED raw=1 "))
            assertNull(LeapmotorTelemetry.snapshot(context))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun smartCleaningAndAcMaxCodeStayRawWithoutInventingTimersOrVehicleCapabilities() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            oemInteger(client, 1219, 1); oemInteger(client, 1217, 2); oemInteger(client, 1003, 2)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=HVAC_SMART_CLEAR_STATUS status=OBSERVED_UNVERIFIED raw=1 "))
            assertTrue(report.contains("channel=HVAC_AUTO_CLEAR_STATUS status=OBSERVED_UNVERIFIED raw=2 "))
            assertTrue(report.contains("channel=HVAC_AC_STATUS status=OBSERVED_UNVERIFIED raw=2 "))
            climate(client, 1219, 1.0)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=HVAC_SMART_CLEAR_STATUS status=UNAVAILABLE raw=UNKNOWN"))
            assertTrue(report.contains("channel=HVAC_AUTO_CLEAR_STATUS status=OBSERVED_UNVERIFIED raw=2 "))
            assertTrue(report.contains("channel=HVAC_AC_STATUS status=OBSERVED_UNVERIFIED raw=2 "))
            assertNull(LeapmotorTelemetry.snapshot(context))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun equalFrontTemperaturesDoNotInventSyncAndSyncFeedbackDoesNotChangeTheSetpoints() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            climate(client, 11205, 20.0); climate(client, 11206, 20.0)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains(
                "channel=HVAC_SYNC_FEEDBACK status=UNAVAILABLE raw=UNKNOWN"))
            for (raw in listOf(1, 2, 0, -1)) {
                oemInteger(client, 7606, raw)
                val report = LeapmotorTelemetry.diagnosticReport(context)
                assertTrue(report.contains("channel=HVAC_SYNC_FEEDBACK status=OBSERVED_UNVERIFIED raw=$raw "))
                assertTrue(report.contains("side=LEFT celsius=20.0 "))
                assertTrue(report.contains("side=RIGHT celsius=20.0 "))
            }
            climate(client, 7606, 1.0)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains(
                "channel=HVAC_SYNC_FEEDBACK status=UNAVAILABLE raw=UNKNOWN"))
            assertNull(LeapmotorTelemetry.snapshot(context))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun rearDisplayedTemperatureDoesNotScaleOrReplaceTheFrontSetpoints() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            climate(client, 11205, 22.5); climate(client, 11206, 23.0); climate(client, 9800, 20.5)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=REAR_HVAC_DISPLAY_TEMPERATURE status=OBSERVED_UNVERIFIED raw=20.5 "))
            assertTrue(report.contains("side=LEFT celsius=22.5 "))
            assertTrue(report.contains("side=RIGHT celsius=23.0 "))
            climate(client, 9800, 41.0)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=REAR_HVAC_DISPLAY_TEMPERATURE status=UNAVAILABLE raw=UNKNOWN"))
            assertTrue(report.contains("side=LEFT celsius=22.5 "))
            assertNull(LeapmotorTelemetry.snapshot(context))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun rearClimateReadingsStaySeparateFromFrontClimateAndDoNotImplyInstalledEquipment() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            oemInteger(client, 1001, 0); oemInteger(client, 9801, 1); oemInteger(client, 9805, 7)
            oemInteger(client, 9802, 2); oemInteger(client, 9803, 1)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=REAR_HVAC_POWER_STATUS status=OBSERVED_UNVERIFIED raw=1 "))
            assertTrue(report.contains("channel=REAR_HVAC_FAN_LEVEL status=OBSERVED_UNVERIFIED raw=7 "))
            assertTrue(report.contains("channel=REAR_HVAC_AIR_DISTRIBUTION status=OBSERVED_UNVERIFIED raw=2 "))
            assertTrue(report.contains("channel=REAR_HVAC_AUTO_STATUS status=OBSERVED_UNVERIFIED raw=1 "))
            oemInteger(client, 9805, 8)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=REAR_HVAC_FAN_LEVEL status=UNAVAILABLE raw=UNKNOWN"))
            assertTrue(report.contains("channel=HVAC_POWER_STATUS status=OBSERVED_UNVERIFIED raw=0 "))
            assertTrue(report.contains("channel=REAR_HVAC_POWER_STATUS status=OBSERVED_UNVERIFIED raw=1 "))
            assertNull(LeapmotorTelemetry.snapshot(context))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun particulateLevelAndAutoCleaningStayDiagnosticWithoutInventingClimateMeasurements() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            oemInteger(client, 1107, 6); oemInteger(client, 1217, 2); oemInteger(client, 1003, 1)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=CABIN_PM25_LEVEL status=OBSERVED_UNVERIFIED raw=6 "))
            assertTrue(report.contains("channel=HVAC_AUTO_CLEAR_STATUS status=OBSERVED_UNVERIFIED raw=2 "))
            oemInteger(client, 1107, 0)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=CABIN_PM25_LEVEL status=UNAVAILABLE raw=UNKNOWN"))
            assertTrue(report.contains("channel=HVAC_AUTO_CLEAR_STATUS status=OBSERVED_UNVERIFIED raw=2 "))
            assertTrue(report.contains("channel=HVAC_AC_STATUS status=OBSERVED_UNVERIFIED raw=1 "))
            assertNull(LeapmotorTelemetry.snapshot(context))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun seatPositionFeedbackIsIndependentOfSeatHeatAndDoesNotBecomeVehicleCapabilities() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            oemInteger(client, 2300, 25); oemInteger(client, 2302, 50); oemInteger(client, 2306, 75)
            oemInteger(client, 10500, 2)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=FRONT_LEFT_SEAT_SLIDE_POSITION status=OBSERVED_UNVERIFIED raw=25 "))
            assertTrue(report.contains("channel=FRONT_LEFT_SEAT_BASE_FRONT_POSITION status=OBSERVED_UNVERIFIED raw=50 "))
            assertTrue(report.contains("channel=FRONT_LEFT_SEAT_BACKREST_POSITION status=OBSERVED_UNVERIFIED raw=75 "))
            oemInteger(client, 2300, 101)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=FRONT_LEFT_SEAT_SLIDE_POSITION status=UNAVAILABLE raw=UNKNOWN"))
            assertTrue(report.contains("channel=FRONT_LEFT_SEAT_BACKREST_POSITION status=OBSERVED_UNVERIFIED raw=75 "))
            assertTrue(report.contains("channel=DRIVER_SEAT_HEAT status=OBSERVED_UNVERIFIED raw=2 "))
            assertNull(LeapmotorTelemetry.snapshot(context))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun acLockAndOnboardChargerCannotFabricateAnEvChargingVerdict() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            oemInteger(client, 6207, 1); oemInteger(client, 5702, 0)
            oemInteger(client, 2509, 2); oemInteger(client, 6100, 3)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=AC_PLUG_LOCK_STATUS raw=2 "))
            assertTrue(report.contains("channel=ONBOARD_CHARGER_STATUS raw=3 "))
            assertTrue(report.contains("channel=CHARGE_STATUS raw=0 "))
            climate(client, 2509, 1.0)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("status=UNAVAILABLE channel=AC_PLUG_LOCK_STATUS raw=UNKNOWN"))
            assertTrue(report.contains("channel=ONBOARD_CHARGER_STATUS raw=3 "))
            assertNull(LeapmotorTelemetry.snapshot(context))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun rangeStandardDiagnosticsCannotReplaceTheExistingEvRangeOrSoc() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            deliver(client)
            val before = LeapmotorTelemetry.snapshot(context)
            assertNotNull(before)
            oemInteger(client, 11102, 250); oemInteger(client, 11103, 220); oemInteger(client, 11104, 3)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("module=ENERGY channel=CLTC_RANGE status=OBSERVED_UNVERIFIED raw=250 "))
            assertTrue(report.contains("module=ENERGY channel=WLTC_RANGE status=OBSERVED_UNVERIFIED raw=220 "))
            assertTrue(report.contains("channel=RANGE_STANDARD status=OBSERVED_UNVERIFIED raw=3 "))
            assertEquals(before, LeapmotorTelemetry.snapshot(context))
            oemInteger(client, 11102, -1)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=CLTC_RANGE status=UNAVAILABLE raw=UNKNOWN"))
            assertTrue(report.contains("channel=WLTC_RANGE status=OBSERVED_UNVERIFIED raw=220 "))
            assertEquals(before, LeapmotorTelemetry.snapshot(context))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun poweredTailgateFieldsRemainSeparateFromTheBodySensorAndRejectInvalidPercentages() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            oemInteger(client, 3823, 2); oemInteger(client, 802, 4); oemInteger(client, 815, 50)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=TAILGATE_POSITION raw=50 "))
            assertTrue(report.contains("channel=POWER_TAILGATE_STATUS raw=4 "))
            assertTrue(report.contains("channel=TAILGATE_STATUS raw=2 "))
            oemInteger(client, 815, 101)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("status=UNAVAILABLE channel=TAILGATE_POSITION raw=UNKNOWN"))
            assertTrue(report.contains("channel=POWER_TAILGATE_STATUS raw=4 "))
            assertTrue(report.contains("channel=TAILGATE_STATUS raw=2 "))
            assertNull(LeapmotorTelemetry.snapshot(context))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun mirrorHeatCodesKeepBothSidesAndDoNotBecomeClimateOrEvValues() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            oemInteger(client, 1700, 1); oemInteger(client, 1800, 101)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=LEFT_MIRROR_HEAT_STATUS status=OBSERVED_UNVERIFIED raw=1 "))
            assertTrue(report.contains("channel=RIGHT_MIRROR_HEAT_STATUS status=OBSERVED_UNVERIFIED raw=101 "))
            climate(client, 1700, 1.0)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=LEFT_MIRROR_HEAT_STATUS status=UNAVAILABLE raw=UNKNOWN"))
            assertTrue(report.contains("channel=RIGHT_MIRROR_HEAT_STATUS status=OBSERVED_UNVERIFIED raw=101 "))
            assertNull(LeapmotorTelemetry.snapshot(context))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun brakeControllerCodesNeverAuthorizeParkingOrAdvertiseVehicleCapabilities() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            oemInteger(client, 5211, 2); oemInteger(client, 4101, 3)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=BRAKE_CONTROLLER_STATUS status=OBSERVED_UNVERIFIED raw=3 "))
            assertTrue(report.contains("channel=GEAR status=OBSERVED_UNVERIFIED raw=2 "))
            climate(client, 4101, 1.0)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=BRAKE_CONTROLLER_STATUS status=UNAVAILABLE raw=UNKNOWN"))
            assertTrue(report.contains("channel=GEAR status=OBSERVED_UNVERIFIED raw=2 "))
            assertNull(LeapmotorTelemetry.snapshot(context))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun mainLampFeedbackIsReadOnlyAndDoesNotBecomeLowBeamOrEvStatus() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            oemInteger(client, 3102, 0); oemInteger(client, 3813, 7)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=MAIN_LAMP_FEEDBACK status=OBSERVED_UNVERIFIED raw=7 "))
            assertTrue(report.contains("channel=LOW_BEAM_STATUS status=OBSERVED_UNVERIFIED raw=0 "))
            climate(client, 3813, 1.0)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=MAIN_LAMP_FEEDBACK status=UNAVAILABLE raw=UNKNOWN"))
            assertTrue(report.contains("channel=LOW_BEAM_STATUS status=OBSERVED_UNVERIFIED raw=0 "))
            assertNull(LeapmotorTelemetry.snapshot(context))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun frontOccupancyCodesStaySeparateFromSeatLevelsAndDoNotBecomeOccupancyVerdicts() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            oemInteger(client, 3806, 2); oemInteger(client, 3815, 0); oemInteger(client, 10500, 3)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=FRONT_PRIMARY_OCCUPANCY_STATUS status=OBSERVED_UNVERIFIED raw=2 "))
            assertTrue(report.contains("channel=FRONT_SECONDARY_OCCUPANCY_STATUS status=OBSERVED_UNVERIFIED raw=0 "))
            assertTrue(report.contains("channel=DRIVER_SEAT_HEAT status=OBSERVED_UNVERIFIED raw=3 "))
            // Codes outside the two OEM-recognized cases remain raw, never default empty/occupied.
            oemInteger(client, 3806, -1)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=FRONT_PRIMARY_OCCUPANCY_STATUS status=OBSERVED_UNVERIFIED raw=-1 "))
            assertTrue(report.contains("channel=FRONT_SECONDARY_OCCUPANCY_STATUS status=OBSERVED_UNVERIFIED raw=0 "))
            climate(client, 3815, 1.0)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains(
                "channel=FRONT_SECONDARY_OCCUPANCY_STATUS status=UNAVAILABLE raw=UNKNOWN"))
            assertNull(LeapmotorTelemetry.snapshot(context))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun rearReadingLightsKeepTheirOwnSidesWithoutBecomingAmbientColorOrEvData() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            deliver(client)
            val before = LeapmotorTelemetry.snapshot(context)
            oemInteger(client, 2702, 2); oemInteger(client, 2703, 1)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=REAR_LEFT_READING_LIGHT_STATUS status=OBSERVED_UNVERIFIED raw=2 "))
            assertTrue(report.contains("channel=REAR_RIGHT_READING_LIGHT_STATUS status=OBSERVED_UNVERIFIED raw=1 "))
            climate(client, 2702, 1.0)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=REAR_LEFT_READING_LIGHT_STATUS status=UNAVAILABLE raw=UNKNOWN"))
            assertTrue(report.contains("channel=REAR_RIGHT_READING_LIGHT_STATUS status=OBSERVED_UNVERIFIED raw=1 "))
            assertFalse(report.contains("channel=AMBIENT_COLOR"))
            assertEquals(before, LeapmotorTelemetry.snapshot(context))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun hoodCentralAndChildLocksRemainIndependentWithoutAnAllLockedInference() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            deliver(client)
            val before = LeapmotorTelemetry.snapshot(context)
            oemInteger(client, 3824, 1); oemInteger(client, 304, 2)
            oemInteger(client, 610, 1); oemInteger(client, 511, 0)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            for (expected in listOf("HOOD_STATUS raw=1", "VEHICLE_LOCK_STATUS raw=2",
                "REAR_LEFT_CHILD_LOCK_STATUS raw=1", "REAR_RIGHT_CHILD_LOCK_STATUS raw=0")) {
                assertTrue(report.contains("C11 DOORS status=OBSERVED_UNVERIFIED channel=$expected "))
            }
            oemInteger(client, 3824, 2)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("C11 DOORS status=UNAVAILABLE channel=HOOD_STATUS raw=UNKNOWN"))
            assertTrue(report.contains("channel=VEHICLE_LOCK_STATUS raw=2 "))
            // Command ID 303 is never accepted as a vehicle-lock observation.
            oemInteger(client, 303, 0)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains("channel=VEHICLE_LOCK_STATUS raw=2 "))
            assertEquals(before, LeapmotorTelemetry.snapshot(context))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun recirculationAndAutomaticModeStaySeparateAndEqualTargetsDoNotInventSync() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            climate(client, 11205, 20.0); climate(client, 11206, 20.0)
            oemInteger(client, 1010, 2); oemInteger(client, 1310, 1)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=HVAC_RECIRCULATION_STATUS status=OBSERVED_UNVERIFIED raw=2 "))
            assertTrue(report.contains("channel=HVAC_RECIRCULATION_AUTO_STATUS status=OBSERVED_UNVERIFIED raw=1 "))
            assertTrue(report.contains("channel=HVAC_SYNC_FEEDBACK status=UNAVAILABLE raw=UNKNOWN"))
            assertFalse(report.contains("sync=true"))
            // Opposite automatic code must not rewrite the last received position.
            oemInteger(client, 1310, 0)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=HVAC_RECIRCULATION_STATUS status=OBSERVED_UNVERIFIED raw=2 "))
            climate(client, 1010, 2.0) // Wrong wire type withdraws only position.
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=HVAC_RECIRCULATION_STATUS status=UNAVAILABLE raw=UNKNOWN"))
            assertTrue(report.contains("channel=HVAC_RECIRCULATION_AUTO_STATUS status=OBSERVED_UNVERIFIED raw=0 "))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun remainingChargeTimeAndTargetReachDiagnosticsWithOemInvalidityAndNoIphoneInference() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            deliver(client)
            val before = LeapmotorTelemetry.snapshot(context)
            oemInteger(client, 5908, 90); oemInteger(client, 11307, 80)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=CHARGE_TIME_REMAINING raw=90 "))
            assertTrue(report.contains("channel=CHARGE_TARGET_PERCENT raw=80 "))
            oemInteger(client, 5908, 0xffffff)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("C11 CHARGING status=UNAVAILABLE channel=CHARGE_TIME_REMAINING raw=UNKNOWN"))
            assertTrue(report.contains("channel=CHARGE_TARGET_PERCENT raw=80 "))
            oemInteger(client, 11307, 49)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains(
                "C11 CHARGING status=UNAVAILABLE channel=CHARGE_TARGET_PERCENT raw=UNKNOWN"))
            assertEquals(before, LeapmotorTelemetry.snapshot(context))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun remainingIntegerModulesReachDiagnosticsAndPreserveLegacySunshadeAndEvState() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            deliver(client)
            val before = LeapmotorTelemetry.snapshot(context)
            val events = mapOf(2900 to 25, 2801 to 30, 10500 to 2, 3814 to 0, 5211 to 1,
                9004 to 2, 1001 to 1, 1012 to 0)
            events.forEach { (event, value) -> oemInteger(client, event, value) }
            val report = LeapmotorTelemetry.diagnosticReport(context)
            for (expected in listOf("WINDOWS_ROOF channel=FRONT_LEFT_WINDOW_POSITION", "SEATS channel=DRIVER_SEAT_HEAT",
                "LIGHTS channel=HIGH_BEAM_STATUS", "DRIVING channel=GEAR", "WARNINGS channel=FRONT_PRIMARY_BELT",
                "CLIMATE channel=HVAC_POWER_STATUS")) assertTrue(report.contains("C11 SUBSYSTEM module=$expected"))
            assertTrue(report.contains("positionRaw=30 oemUiPosition=70"))
            assertEquals(before, LeapmotorTelemetry.snapshot(context))
            assertEquals(1, context.connections.size)
            oemInteger(client, 10500, 4)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains(
                "channel=DRIVER_SEAT_HEAT status=UNAVAILABLE raw=UNKNOWN"))
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains("channel=GEAR status=OBSERVED_UNVERIFIED raw=1 "))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun measurementAndSubsystemSourcesExpireIndependentlyAndRetiredBatchesCannotReturn() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val connection = context.connections.single()
            val retired = attach(connection)
            fun energy(client: Messenger, value: Double) {
                client.send(Message.obtain(null, 2).apply { data = Bundle().apply {
                    putInt("service", 4161); putInt("instance", 20545); putInt("group", 17920)
                    putInt("event", 12291); putByteArray("payload", ByteBuffer.allocate(8)
                        .order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array())
                    putLong("receivedAt", SystemClock.elapsedRealtime())
                } })
                idle()
            }
            energy(retired, 2.0); energy(retired, 3.0)
            oemInteger(retired, 2900, 25); oemInteger(retired, 2900, 50)
            val current = attach(connection)
            ShadowLog.clear()
            advanceLogWindow(1_000)
            assertTrue(logs("DiPlay-SUBSYSTEM").isEmpty())
            assertTrue(logs("DiPlay-MEASUREMENTS").isEmpty())
            energy(current, 4.0); energy(retired, 9.0)
            oemInteger(current, 2900, 75); oemInteger(retired, 2900, 90)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=CURRENT_TRIP_CONSUMPTION status=OBSERVED_UNVERIFIED raw=4.0 "))
            assertEquals(4.0, LeapmotorTelemetry.currentTripConsumption(context)!!, 0.0)
            assertTrue(report.contains("channel=FRONT_LEFT_WINDOW_POSITION status=OBSERVED_UNVERIFIED raw=75 "))
            energy(current, -1.0)
            assertNull(LeapmotorTelemetry.currentTripConsumption(context))
            energy(current, 0.0)
            assertEquals(0.0, LeapmotorTelemetry.currentTripConsumption(context)!!, 0.0)
            energy(current, 4.0)
            advanceLogWindow(60_001)
            climate(current, 4030, 20.0)
            oemInteger(current, 3814, 1)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("channel=CURRENT_TRIP_CONSUMPTION status=STALE raw=UNKNOWN"))
            assertNull(LeapmotorTelemetry.currentTripConsumption(context))
            assertTrue(report.contains("channel=FRONT_LEFT_WINDOW_POSITION status=STALE raw=UNKNOWN"))
            energy(current, 4.5)
            connection.onServiceDisconnected(component)
            assertNull(LeapmotorTelemetry.currentTripConsumption(context))
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains(
                "channel=CURRENT_TRIP_CONSUMPTION status=UNAVAILABLE raw=UNKNOWN"))
            ShadowLog.clear(); advanceLogWindow(1_000)
            assertTrue(logs("DiPlay-SUBSYSTEM").isEmpty())
            assertTrue(logs("DiPlay-MEASUREMENTS").isEmpty())
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun doorChannelsStaySeparateAndCannotChangeChargingOrIphoneTelemetry() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            deliver(client)
            oemInteger(client, 5702, 1)
            val before = LeapmotorTelemetry.snapshot(context)
            oemInteger(client, 3860, 0); oemInteger(client, 3820, 1)
            oemInteger(client, 3821, 2); oemInteger(client, 3822, -1); oemInteger(client, 3823, 255)
            val report = LeapmotorTelemetry.diagnosticReport(context)
            for (expected in listOf("DRIVER_DOOR raw=0", "PASSENGER_DOOR raw=1", "REAR_LEFT_DOOR raw=2",
                "REAR_RIGHT_DOOR raw=-1", "TAILGATE_STATUS raw=255")) {
                assertTrue(report.contains("C11 DOORS status=OBSERVED_UNVERIFIED channel=$expected "))
            }
            assertTrue(report.contains("channel=CHARGE_STATUS raw=1 "))
            assertEquals(before, LeapmotorTelemetry.snapshot(context))
            climate(client, 3823, 1.0) // Wrong wire size withdraws only tailgate.
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains(
                "C11 DOORS status=UNAVAILABLE channel=TAILGATE_STATUS raw=UNKNOWN"))
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains("channel=DRIVER_DOOR raw=0 "))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun doorSourceReplacementAndLossCancelLogsAndEachDoorExpiresIndependently() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val connection = context.connections.single()
            val retired = attach(connection)
            oemInteger(retired, 3860, 0); oemInteger(retired, 3860, 1)
            val current = attach(connection)
            ShadowLog.clear()
            advanceLogWindow(1_000)
            assertTrue(logs("DiPlay-DOORS").isEmpty())
            oemInteger(current, 3860, 2)
            oemInteger(retired, 3860, 1)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains("channel=DRIVER_DOOR raw=2 "))
            advanceLogWindow(60_001)
            oemInteger(current, 3820, 0)
            val report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("C11 DOORS status=STALE channel=DRIVER_DOOR raw=UNKNOWN"))
            assertTrue(report.contains("channel=PASSENGER_DOOR raw=0 "))
            oemInteger(current, 3820, 1)
            connection.onServiceDisconnected(component)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains(
                "C11 DOORS status=UNAVAILABLE channel=PASSENGER_DOOR raw=UNKNOWN"))
            ShadowLog.clear()
            advanceLogWindow(1_000)
            assertTrue(logs("DiPlay-DOORS").isEmpty())
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun chargingMessagesReachDiagnosticsWithoutChangingTheIphoneVehicleStatus() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            deliver(client)
            ShadowLog.clear()
            oemInteger(client, 5702, 1)
            oemInteger(client, 6207, 0)
            oemInteger(client, 5905, -1)
            val report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("C11 CHARGING status=OBSERVED_UNVERIFIED channel=CHARGE_STATUS raw=1 "))
            assertTrue(report.contains("channel=AC_CONNECTION raw=0 "))
            assertTrue(report.contains("channel=DC_CONNECTION raw=-1 "))
            assertNull(LeapmotorTelemetry.snapshot(context)!!.charging)
            assertEquals(227, LeapmotorTelemetry.snapshot(context)!!.rangeKm)
            advanceLogWindow(1_000)
            assertTrue(logs("DiPlay-CHARGING").any { it.contains("channel=DC_CONNECTION raw=-1 ") })
            // A malformed double for the INT channel withdraws only that channel.
            climate(client, 6207, 1.0)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains(
                "C11 CHARGING status=UNAVAILABLE channel=AC_CONNECTION raw=UNKNOWN"))
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains("channel=CHARGE_STATUS raw=1 "))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun chargingSourceReplacementSilenceAndLossWithdrawValuesAndCancelLogs() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val connection = context.connections.single()
            val retired = attach(connection)
            oemInteger(retired, 5702, 1)
            oemInteger(retired, 5702, 2) // Pending trailing batch must belong to this source only.
            val current = attach(connection)
            ShadowLog.clear()
            advanceLogWindow(1_000)
            assertTrue(logs("DiPlay-CHARGING").isEmpty())
            oemInteger(current, 5702, 3)
            oemInteger(retired, 5702, 1)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains("channel=CHARGE_STATUS raw=3 "))
            advanceLogWindow(60_001)
            oemInteger(current, 6207, 0)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains(
                "C11 CHARGING status=STALE channel=CHARGE_STATUS raw=UNKNOWN"))
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains("channel=AC_CONNECTION raw=0 "))
            connection.onServiceDisconnected(component)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains(
                "C11 CHARGING status=UNAVAILABLE channel=AC_CONNECTION raw=UNKNOWN"))
            ShadowLog.clear()
            advanceLogWindow(1_000)
            assertTrue(logs("DiPlay-CHARGING").isEmpty())
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun qnxWidgetPressuresReachLocalUiCacheAndWithdrawOnInvalidityOrSourceLoss() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val connection = context.connections.single()
            val retired = attach(connection)
            fun pressure(client: Messenger, event: Int, text: String) {
                client.send(Message.obtain(null, 2).apply { data = Bundle().apply {
                    putInt("service", 29185); putInt("instance", 29186); putInt("group", 29184)
                    putInt("event", event); putByteArray("payload", text.toByteArray())
                    putLong("receivedAt", SystemClock.elapsedRealtime())
                } })
                idle()
            }
            val wheel = com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorQnxTireState.Wheel.FRONT_LEFT
            pressure(retired, 1109, "233|0")
            assertEquals(233, LeapmotorTelemetry.tirePressures(context)[wheel])
            pressure(retired, 1113, "19|0")
            assertEquals(19, LeapmotorTelemetry.tireTemperatures(context)[wheel])
            pressure(retired, 1113, "19|1")
            assertNull(LeapmotorTelemetry.tireTemperatures(context)[wheel])
            assertEquals(233, LeapmotorTelemetry.tirePressures(context)[wheel])
            pressure(retired, 1109, "233|1")
            assertNull(LeapmotorTelemetry.tirePressures(context)[wheel])
            val current = attach(connection)
            pressure(current, 1109, "234|0")
            pressure(current, 1113, "-1|0")
            pressure(retired, 1113, "25|0")
            assertEquals(-1, LeapmotorTelemetry.tireTemperatures(context)[wheel])
            pressure(retired, 1109, "200|0")
            assertEquals(234, LeapmotorTelemetry.tirePressures(context)[wheel])
            advanceLogWindow(60_001)
            assertNull(LeapmotorTelemetry.tirePressures(context)[wheel])
            assertNull(LeapmotorTelemetry.tireTemperatures(context)[wheel])
            pressure(current, 1109, "235|0")
            connection.onServiceDisconnected(component)
            assertTrue(LeapmotorTelemetry.tirePressures(context).isEmpty())
            assertTrue(LeapmotorTelemetry.tireTemperatures(context).isEmpty())
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun fanAndFlagBatchesEmitLatestWithoutAnotherEventAndKeepUnknownConstituents() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            ShadowLog.clear()
            oemInteger(client, 1008, 2); oemInteger(client, 1008, 7)
            oemInteger(client, 3301, 0)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains(
                "wheel=FRONT_LEFT pressureCheck=UNKNOWN temperatureCheck=UNKNOWN leakRaw=0"))
            oemInteger(client, 3302, 0); oemInteger(client, 3303, 0)
            oemInteger(client, 3302, 9)
            assertEquals(1, logs("DiPlay-HVAC-FAN").size)
            assertEquals(4, logs("DiPlay-TPMS-FLAGS").size)
            assertFalse(logs("DiPlay-HVAC-FAN").any { it.contains("level=7 ") })
            advanceLogWindow(1_000)
            assertEquals(2, logs("DiPlay-HVAC-FAN").size)
            assertTrue(logs("DiPlay-HVAC-FAN").last().contains("status=OBSERVED level=7 "))
            assertEquals(8, logs("DiPlay-TPMS-FLAGS").size)
            assertTrue(logs("DiPlay-TPMS-FLAGS").any { it.contains(
                "wheel=FRONT_LEFT pressureCheck=NONZERO_FLAG_OBSERVED temperatureCheck=ZERO_FLAGS_OBSERVED") &&
                it.contains("pressureRaw=9 ") })
            assertNull(LeapmotorTelemetry.snapshot(context)) // These diagnostics do not fabricate SOC/range.
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun fanAndFlagRetiredEpochsCancelTrailingLogsAndNeverOverwriteReplacement() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val connection = context.connections.single()
            val retired = attach(connection)
            oemInteger(retired, 1008, 2); oemInteger(retired, 1008, 3)
            oemInteger(retired, 3301, 0); oemInteger(retired, 3302, 0)
            val current = attach(connection)
            ShadowLog.clear()
            advanceLogWindow(1_000)
            assertTrue(logs("DiPlay-HVAC-FAN").isEmpty())
            assertTrue(logs("DiPlay-TPMS-FLAGS").isEmpty())
            oemInteger(current, 1008, 5); oemInteger(current, 3301, 6)
            oemInteger(retired, 1008, 1); oemInteger(retired, 3301, 0)
            val report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("C11 HVAC_FAN status=OBSERVED level=5 "))
            assertTrue(report.contains("temperatureCheck=UNKNOWN leakRaw=6 "))
            connection.onServiceDisconnected(component)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains(
                "C11 HVAC_FAN status=UNAVAILABLE level=UNKNOWN"))
            assertFalse(LeapmotorTelemetry.diagnosticReport(context).contains("leakRaw=6 "))
            ShadowLog.clear()
            advanceLogWindow(1_000)
            assertTrue(logs("DiPlay-HVAC-FAN").isEmpty())
            assertTrue(logs("DiPlay-TPMS-FLAGS").isEmpty())
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun fanSilenceAndInvalidLevelWithdrawWhileFlagsExpireIndependently() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            oemInteger(client, 1008, 4); oemInteger(client, 1008, 8)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains(
                "C11 HVAC_FAN status=UNAVAILABLE level=UNKNOWN"))
            oemInteger(client, 1008, 0)
            oemInteger(client, 3301, 0); oemInteger(client, 3302, 0); oemInteger(client, 3303, 0)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains(
                "wheel=FRONT_LEFT pressureCheck=ZERO_FLAGS_OBSERVED temperatureCheck=ZERO_FLAGS_OBSERVED"))
            advanceLogWindow(60_001)
            val report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("C11 HVAC_FAN status=STALE level=UNKNOWN"))
            assertTrue(report.contains("wheel=FRONT_LEFT pressureCheck=UNKNOWN temperatureCheck=UNKNOWN leakRaw=UNKNOWN"))
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun sameInstantFieldsEmitLatestTrailingBatchForEveryDiagnosticFamily() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            ShadowLog.clear()
            climate(client, 1221, 24.5); climate(client, 1016, 15.0)
            climate(client, 11205, 22.5); climate(client, 11206, 25.0)
            oemInteger(client, 3403, 240); oemInteger(client, 3503, 245)
            oemInteger(client, 2801, 0); oemInteger(client, 2801, 20)
            assertFalse(logs("DiPlay-AMBIENT").any { it.contains("exteriorCorrectedRawDouble=15.0") })
            assertFalse(logs("DiPlay-HVAC").any { it.contains("side=RIGHT celsius=25.0") })
            assertFalse(logs("DiPlay-TPMS").any { it.contains("wheel=FRONT_RIGHT pressureRaw=245") })
            assertFalse(logs("DiPlay-SUNSHADE").any { it.contains("positionRaw=20 ") })
            advanceLogWindow(999)
            assertEquals(2, logs("DiPlay-AMBIENT").size)
            assertEquals(2, logs("DiPlay-HVAC").size)
            assertEquals(8, logs("DiPlay-TPMS").size)
            assertEquals(1, logs("DiPlay-SUNSHADE").size)
            advanceLogWindow(1)
            assertTrue(logs("DiPlay-AMBIENT").any { it.contains("exteriorCorrectedRawDouble=15.0") })
            assertTrue(logs("DiPlay-HVAC").any { it.contains("side=RIGHT celsius=25.0") })
            assertTrue(logs("DiPlay-TPMS").any { it.contains("wheel=FRONT_RIGHT pressureRaw=245") })
            assertTrue(logs("DiPlay-SUNSHADE").any { it.contains("positionRaw=20 oemUiPosition=80") })
            assertEquals(4, logs("DiPlay-AMBIENT").size)
            assertEquals(4, logs("DiPlay-HVAC").size)
            assertEquals(16, logs("DiPlay-TPMS").size)
            assertEquals(2, logs("DiPlay-SUNSHADE").size)
            advanceLogWindow(3_000)
            assertEquals(4, logs("DiPlay-AMBIENT").size) // No polling or repetitive idle logs.
            assertEquals(2, logs("DiPlay-SUNSHADE").size)
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun lastChangeInsideWindowIsEmittedWithoutAnotherOemEvent() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val client = attach(context.connections.single())
            ShadowLog.clear()
            climate(client, 1221, 24.5)
            advanceLogWindow(250); climate(client, 1221, 25.0)
            advanceLogWindow(250); climate(client, 1221, 26.0)
            assertEquals(2, logs("DiPlay-AMBIENT").size)
            advanceLogWindow(500)
            assertTrue(logs("DiPlay-AMBIENT").any { it.contains("interiorRawDouble=26.0") })
            assertFalse(logs("DiPlay-AMBIENT").any { it.contains("interiorRawDouble=25.0") })
            advanceLogWindow(400); climate(client, 1221, 27.0)
            advanceLogWindow(599)
            assertEquals(4, logs("DiPlay-AMBIENT").size)
            advanceLogWindow(1)
            assertTrue(logs("DiPlay-AMBIENT").any { it.contains("interiorRawDouble=27.0") })
            assertEquals(6, logs("DiPlay-AMBIENT").size)
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun retiredAndSameConnectionReplacementCancelPendingDiagnosticBatches() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val connection = context.connections.single()
            val retired = attach(connection)
            climate(retired, 1221, 24.0); climate(retired, 1016, 15.0)
            climate(retired, 11205, 22.0); climate(retired, 11206, 25.0)
            oemInteger(retired, 3403, 240); oemInteger(retired, 3503, 245)
            oemInteger(retired, 2801, 0); oemInteger(retired, 2801, 20)
            // Even the same ServiceConnection can acquire a new source epoch and reply Messenger.
            val current = attach(connection)
            ShadowLog.clear()
            advanceLogWindow(1_000)
            for (tag in listOf("DiPlay-AMBIENT", "DiPlay-HVAC", "DiPlay-TPMS", "DiPlay-SUNSHADE"))
                assertTrue(logs(tag).isEmpty())
            climate(retired, 1016, 16.0)
            assertTrue(logs("DiPlay-AMBIENT").isEmpty())
            climate(current, 1221, 30.0); climate(current, 1016, 18.0)
            connection.onServiceDisconnected(component)
            ShadowLog.clear()
            advanceLogWindow(1_000)
            assertTrue(logs("DiPlay-AMBIENT").isEmpty())
            LeapmotorTelemetry.acquire(context, owner); idle()
            val replacement = attach(context.connections.last())
            climate(replacement, 1016, 19.0)
            assertTrue(logs("DiPlay-AMBIENT").any { it.contains("exteriorCorrectedRawDouble=19.0") })
            assertFalse(logs("DiPlay-AMBIENT").any { it.contains("interiorRawDouble=30.0") })
        } finally { LeapmotorTelemetry.release(owner); idle() }
    }

    @Test fun ambientRawDiagnosticsDoNotClaimUnitsOrCurrentTemperatureValidity() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val connection = context.connections.single()
            val retired = attach(connection)
            climate(retired, 1221, 23.125)
            climate(retired, 1016, -1.25)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("status=OBSERVED_UNVERIFIED channel=INTERIOR interiorRawDouble=23.125"))
            assertTrue(report.contains("exteriorCorrectedRawDouble=-1.25"))
            assertTrue(report.contains("sensorValidity=UNKNOWN"))
            assertFalse(report.contains("celsius=23.125"))
            climate(retired, 1221, Double.POSITIVE_INFINITY)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains("interiorRawDouble=UNKNOWN"))
            climate(retired, 1221, 22.0)
            connection.onServiceDisconnected(component)
            LeapmotorTelemetry.acquire(context, owner); idle()
            val current = attach(context.connections.last())
            climate(current, 1016, 10.0)
            climate(retired, 1221, 24.0)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("interiorRawDouble=UNKNOWN"))
            assertTrue(report.contains("exteriorCorrectedRawDouble=10.0"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains(
                "status=STALE channel=EXTERIOR_CORRECTED exteriorCorrectedRawDouble=UNKNOWN"))
        } finally { LeapmotorTelemetry.release(owner); idle() }
        assertFalse(LeapmotorTelemetry.diagnosticReport(context).contains("status=OBSERVED_UNVERIFIED"))
    }

    @Test fun tireDiagnosticsRemainRawAndRetiredConnectionCannotRestoreThem() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val connection = context.connections.single()
            val retired = attach(connection)
            oemInteger(retired, 3403, 240)
            oemInteger(retired, 3404, -1)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("wheel=FRONT_LEFT pressureRaw=240"))
            assertTrue(report.contains("wheel=FRONT_LEFT tempRaw=-1"))
            assertTrue(report.contains("units=UNVERIFIED"))
            oemInteger(retired, 3403, 4095)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains("wheel=FRONT_LEFT pressureRaw=UNKNOWN"))
            connection.onServiceDisconnected(component)
            LeapmotorTelemetry.acquire(context, owner); idle()
            val current = attach(context.connections.last())
            oemInteger(current, 3503, 245)
            oemInteger(retired, 3403, 200)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("wheel=FRONT_RIGHT pressureRaw=245"))
            assertFalse(report.contains("pressureRaw=200"))
            assertFalse(report.contains("tempRaw=-1"))
        } finally { LeapmotorTelemetry.release(owner); idle() }
        assertFalse(LeapmotorTelemetry.diagnosticReport(context).contains("pressureRaw=245"))
    }

    @Test fun roofCurtainDiagnosticsKeepRawInversionAndRejectGlassAndRetiredCallbacks() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val connection = context.connections.single()
            val retired = attach(connection)
            oemInteger(retired, 2800, 90)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains("positionRaw=UNKNOWN"))
            oemInteger(retired, 2801, 25)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("positionRaw=25 oemUiPosition=75"))
            assertTrue(report.contains("direction=UNVERIFIED"))
            assertTrue(report.contains("stop=UNKNOWN"))
            oemInteger(retired, 2801, 101)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains("positionRaw=UNKNOWN"))
            connection.onServiceDisconnected(component)
            LeapmotorTelemetry.acquire(context, owner); idle()
            val current = attach(context.connections.last())
            oemInteger(current, 2801, 60)
            oemInteger(retired, 2801, 25)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("positionRaw=60 oemUiPosition=40"))
            assertFalse(report.contains("positionRaw=25"))
        } finally { LeapmotorTelemetry.release(owner); idle() }
        assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains("positionRaw=UNKNOWN"))
    }

    @Test fun passiveClimateDiagnosticsDecodeInvalidateExpireAndRejectRetiredSourceCallbacks() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val owner = Any()
        LeapmotorTelemetry.acquire(context, owner)
        try {
            idle()
            val first = context.connections.single()
            val retired = attach(first)
            climate(retired, 11205, 22.5)
            climate(retired, 11206, 25.0)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("status=AVAILABLE side=LEFT celsius=22.5"))
            assertTrue(report.contains("status=AVAILABLE side=RIGHT celsius=25.0"))
            assertTrue(report.contains("writeBackend=UNAVAILABLE"))
            assertFalse(report.contains("payload"))
            climate(retired, 11205, null)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("status=UNAVAILABLE side=LEFT celsius=UNKNOWN"))
            assertTrue(report.contains("status=AVAILABLE side=RIGHT celsius=25.0"))
            first.onServiceDisconnected(component)
            assertFalse(LeapmotorTelemetry.diagnosticReport(context).contains("celsius=25.0"))
            LeapmotorTelemetry.acquire(context, owner)
            idle()
            val current = attach(context.connections.last())
            climate(current, 11205, 24.0)
            climate(retired, 11205, 20.0)
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains("side=LEFT celsius=24.0"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("status=STALE side=LEFT celsius=UNKNOWN"))
            assertTrue(report.length < 16_000) // Fixed schema now also covers 32 subsystem and 7 measurement fields.
        } finally { LeapmotorTelemetry.release(owner); idle() }
        assertFalse(LeapmotorTelemetry.diagnosticReport(context).contains("status=AVAILABLE side="))
    }

    @Test fun otherVehicleProfilesNeverBindToTheOemService() {
        profile(VehicleIntegrationPolicy.BYD)
        val context = FakeContext(app)
        val provider = LeapmotorTelemetry.provider(context)
        try {
            idle()
            assertTrue(context.connections.isEmpty())
            assertNull(provider.snapshot())
        } finally { provider.close(); idle() }
    }

    @Test fun exteriorLeaseSurvivesSomeIpFailureUntilLastExplicitOwnerReleases() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app) // Explicitly denied exterior permission: no platform acquisition.
        val activity = Any()
        LeapmotorTelemetry.acquire(context, activity)
        val provider = LeapmotorTelemetry.provider(context)
        try {
            idle()
            val connection = context.connections.single()
            attach(connection)
            var report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("C11 EXTERIOR: source=CLOSED_OR_DISABLED status=UNAVAILABLE celsius=UNKNOWN"))
            LeapmotorTelemetry.release(activity); idle()
            assertFalse(LeapmotorTelemetry.diagnosticReport(context).contains("C11 EXTERIOR: source=STOPPED"))
            connection.onServiceDisconnected(component)
            report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("C11 EXTERIOR: source=CLOSED_OR_DISABLED"))
            assertFalse(report.contains("C11 EXTERIOR: source=STOPPED"))
        } finally {
            LeapmotorTelemetry.release(activity)
            provider.close(); idle()
        }
        assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains("C11 EXTERIOR: source=STOPPED"))
    }

    @Test fun controllerLeaseSurvivesActivityReleaseAndRejectsDeadConnectionMessages() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val activity = Any()
        LeapmotorTelemetry.acquire(context, activity)
        val provider = LeapmotorTelemetry.provider(context)
        try {
            idle()
            assertEquals(1, context.connections.size)
            val connection = context.connections.single()
            val client = attach(connection)
            deliver(client)
            assertEquals(227, provider.snapshot()!!.rangeKm)
            val report = LeapmotorTelemetry.diagnosticReport(context)
            assertTrue(report.contains("reading=AVAILABLE"))
            assertTrue(report.contains("socPercent=53.1 rangeKm=227"))
            assertTrue(report.contains("originTimestamp=UNKNOWN"))
            LeapmotorTelemetry.release(activity); idle()
            assertEquals(0, context.unbinds)
            assertNotNull(provider.snapshot())
            connection.onServiceDisconnected(component)
            assertNull(provider.snapshot())
            assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains("reading=UNAVAILABLE"))
            deliver(client)
            assertNull(provider.snapshot())
            assertEquals(1, context.connections.size) // No native crash restart loop.
        } finally {
            LeapmotorTelemetry.release(activity)
            provider.close(); idle()
        }
        assertEquals(1, context.unbinds)
        assertNull(provider.snapshot())
    }

    @Test fun unfinishedBindingTimesOutAndAnewOwnerCanRetry() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        val first = LeapmotorTelemetry.provider(context)
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertEquals(1, context.unbinds)
        assertNull(first.snapshot())
        val second = LeapmotorTelemetry.provider(context)
        try {
            idle()
            assertEquals(2, context.connections.size)
            deliver(attach(context.connections.last()))
            assertNotNull(second.snapshot())
        } finally { first.close(); second.close(); idle() }
        assertEquals(2, context.unbinds)
    }

    @Test fun aDiagnosticReportDoesNotStartTheOemSource() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val context = FakeContext(app)
        assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains("reading=UNAVAILABLE"))
        assertTrue(LeapmotorTelemetry.diagnosticReport(context).contains("C11 EXTERIOR: source=STOPPED"))
        idle()
        assertTrue(context.connections.isEmpty())
    }
}
