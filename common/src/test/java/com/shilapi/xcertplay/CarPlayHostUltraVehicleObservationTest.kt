package com.shilapi.xcertplay

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.UltraReferenceCenterHost
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.vehicle.C11UltraSettings
import com.shilapi.xcertplay.vehicle.LeapmotorParkedIntegration
import com.shilapi.xcertplay.vehicle.VehicleIntegrationPolicy
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real Activity ownership/gates, with no platform service, phone transport or vehicle access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostUltraVehicleObservationTest {
    private lateinit var activity: CarPlayHostActivity
    private val leases = mutableListOf<LeapmotorParkedIntegration.Lease>()

    @Before fun prepare() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val info = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_META_DATA)
        info.applicationInfo!!.metaData = Bundle().apply {
            putString(VehicleIntegrationPolicy.METADATA_KEY, VehicleIntegrationPolicy.LEAPMOTOR_C11)
        }
        shadowOf(activity.packageManager).installPackage(info)
        C11UltraSettings.setEnabled(activity, true)
        set("hostAppearanceResumed", true)
        set("referenceCenterHost", mock(UltraReferenceCenterHost::class.java))
        set("activeAirPlaySession", mock(AirPlaySession::class.java))
        VideoInCar.allowed = false
        activity.ultraParkedObservationFactory = {
            mock(LeapmotorParkedIntegration.Lease::class.java).also { lease ->
                `when`(lease.diagnosticSnapshot()).thenReturn(LeapmotorParkedIntegration.Lease.DiagnosticSnapshot(
                    "CONNECTED", true, "AVAILABLE", 60_000, 0, true))
                leases += lease
            }
        }
    }

    @After fun retire() {
        set("hostAppearanceResumed", false)
        refresh()
        C11UltraSettings.setEnabled(activity, false)
        (get("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (get("teardownExecutor") as ExecutorService).shutdownNow()
        (get("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        VideoInCar.allowed = false
    }

    @Test fun observationReusesItsSessionAndNeverEnablesVideoEvenWhenParked() {
        refresh(); refresh()
        assertEquals(1, leases.size)
        verify(leases.single(), never()).close()
        assertFalse(VideoInCar.allowed)
        val logs = org.robolectric.shadows.ShadowLog.getLogsForTag("xcertplay-usb")
            .filter { it.msg.startsWith("C11 parked observation:") }
        assertEquals(1, logs.size)
        assertTrue(logs.single().msg.contains("reading=AVAILABLE parked=true"))
    }

    @Test fun replacingSessionAndPausingReleaseEachOwnerExactlyOnce() {
        refresh()
        val old = leases.single()
        set("activeAirPlaySession", mock(AirPlaySession::class.java))
        refresh()
        assertEquals(2, leases.size)
        verify(old).close()
        set("hostAppearanceResumed", false)
        refresh(); refresh()
        verify(leases[1]).close()
        assertNull(get("ultraParkedObservation"))
    }

    @Test fun sourceIsNotAcquiredWithoutManualOptInVisibleHostAndActiveSession() {
        C11UltraSettings.setEnabled(activity, false)
        refresh()
        C11UltraSettings.setEnabled(activity, true)
        set("hostAppearanceResumed", false)
        refresh()
        set("hostAppearanceResumed", true)
        set("activeAirPlaySession", null)
        refresh()
        set("activeAirPlaySession", mock(AirPlaySession::class.java))
        set("referenceCenterHost", null)
        refresh()
        assertTrue(leases.isEmpty())
    }

    @Test fun acquisitionFailureDoesNotCrashOrRetryUntilTheNextSession() {
        var attempts = 0
        activity.ultraParkedObservationFactory = { attempts++; throw IllegalStateException("simulated source unavailable") }
        refresh(); refresh()
        assertEquals(1, attempts)
        set("activeAirPlaySession", mock(AirPlaySession::class.java))
        refresh()
        assertEquals(2, attempts)
        assertFalse(VideoInCar.allowed)
    }

    @Test fun endedSessionReleasesObservationEvenWhenStartupRetryIsStopped() {
        refresh()
        set("startupRetryStopped", true)
        val ended = get("activeAirPlaySession") as AirPlaySession
        val listener = CarPlayHostActivity::class.java.getDeclaredMethod("createSessionListener", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, get("restartGeneration")) as AirPlaySessionListener
        listener.onSessionEnded(ended)
        shadowOf(Looper.getMainLooper()).idle()
        refresh()
        assertNull(get("activeAirPlaySession"))
        verify(leases.single()).close()
        assertEquals(1, leases.size)
    }

    @Test fun oldEndCannotRestartReplacementBeforeItsUiActiveCallbackArrives() {
        refresh()
        val old = get("activeAirPlaySession") as AirPlaySession
        val replacement = mock(AirPlaySession::class.java)
        val controller = mock(CarPlayController::class.java)
        `when`(controller.currentAirPlaySession()).thenReturn(replacement)
        set("controller", controller)
        val listener = CarPlayHostActivity::class.java.getDeclaredMethod("createSessionListener", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, get("restartGeneration")) as AirPlaySessionListener
        listener.onSessionEnded(old)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(replacement, get("activeAirPlaySession"))
        assertEquals(2, leases.size)
        verify(leases[0]).close()
        verify(leases[1], never()).close()
        verify(controller).currentAirPlaySession()
        verifyNoMoreInteractions(controller)
    }

    private fun refresh() = CarPlayHostActivity::class.java.getDeclaredMethod("refreshUltraVehicleUi")
        .apply { isAccessible = true }.invoke(activity)
    private fun set(name: String, value: Any?) = CarPlayHostActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(activity, value)
    private fun get(name: String): Any? = CarPlayHostActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
}
