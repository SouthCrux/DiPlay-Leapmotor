package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import com.shilapi.xcertplay.hud.BydAdbShell
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.BydOutputSettings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class VehicleIntegrationPolicyTest {
    private lateinit var app: Context

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        select(null)
    }

    private fun select(choice: String?) {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_META_DATA)
        info.applicationInfo!!.metaData = Bundle().apply {
            if (choice != null) putString(VehicleIntegrationPolicy.METADATA_KEY, choice)
        }
        shadowOf(app.packageManager).installPackage(info)
    }

    @Test fun c11AndUnknownExplicitBackendsNeverSelectByd() {
        for (choice in listOf(VehicleIntegrationPolicy.LEAPMOTOR_C11, "unknown", "")) {
            select(choice)
            assertFalse(VehicleIntegrationPolicy.allowsByd(app))
            assertSame(VehicleNavigationOutput.NONE, VehicleNavigationOutput.forContext(app))
        }
    }

    @Test fun upstreamSamplesRetainTheirBackendUnlessExplicitlyChanged() {
        assertTrue(VehicleIntegrationPolicy.allowsByd(app))
        assertSame(BydNavigationOutputs, VehicleNavigationOutput.forContext(app))
        select(VehicleIntegrationPolicy.BYD)
        assertTrue(VehicleIntegrationPolicy.allowsByd(app))
    }

    @Test fun oldSavedVehiclePreferencesCannotEnableBydTelemetryOnC11() {
        BydOutputSettings.setBatteryToIphone(app, true)
        BydOutputSettings.setWheelSpeedToIphone(app, true)
        BydOutputSettings.setVideoWhileParked(app, true)
        select(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        assertFalse(BydOutputSettings.batteryToIphoneActive(app))
        assertFalse(BydOutputSettings.wheelSpeedToIphoneActive(app))
        assertFalse(BydOutputSettings.videoWhileParkedActive(app))
        assertNull(BydNavigationOutputs.batteryStatus(app).snapshot())
        assertNull(BydNavigationOutputs.parked(app))
    }

    @Test fun c11RejectsBydShellBeforeAnAdbConnectionIsAttempted() {
        select(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        val shell = BydAdbShell("test")
        try {
            assertNull(shell.run(app, "service call autoservice 7 i32 1014 i32 123"))
            assertNull(shell.lastAccess)
        } finally {
            shell.close()
        }
    }
}
