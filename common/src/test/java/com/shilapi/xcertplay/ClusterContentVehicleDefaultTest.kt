package com.shilapi.xcertplay

import android.content.pm.PackageManager
import android.os.Bundle
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.vehicle.VehicleIntegrationPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterContentVehicleDefaultTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", 0)
    private var previousMetadata: Bundle? = null

    @Before fun setup() {
        prefs.edit().clear().apply()
        previousMetadata = context.packageManager.getApplicationInfo(context.packageName,
            PackageManager.GET_META_DATA).metaData?.let(::Bundle)
        AirPlayPersistence.overlaySettingsListener = null
    }

    @After fun cleanup() {
        metadata(previousMetadata)
        AirPlayPersistence.overlaySettingsListener = null
        prefs.edit().clear().apply()
    }

    private fun metadata(value: Bundle?) {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_META_DATA)
        info.applicationInfo!!.metaData = value
        shadowOf(context.packageManager).installPackage(info)
    }

    private fun profile(value: String?) = metadata(value?.let {
        Bundle().apply { putString(VehicleIntegrationPolicy.METADATA_KEY, it) }
    })

    @Test fun c11UsesInterpretedCardWithoutWritingPreferencesOrEnablingCluster() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        assertEquals(CarPlayClusterDisplay.Content.MAP_WITH_CUSTOM_CARD,
            AirPlayPersistence.loadClusterContent(context))
        assertFalse(prefs.contains("cluster_content"))
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(context))
    }

    @Test fun everyExplicitC11ChoiceRemainsAuthoritativeIncludingMapOnly() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        CarPlayClusterDisplay.Content.entries.forEach { content ->
            AirPlayPersistence.saveClusterContent(context, content)
            assertEquals(content, AirPlayPersistence.loadClusterContent(context))
        }
    }

    @Test fun missingAndNonC11ProfilesKeepUpstreamMapDefault() {
        listOf(null, VehicleIntegrationPolicy.BYD, "unknown-profile").forEach { profileValue ->
            profile(profileValue)
            assertEquals(CarPlayClusterDisplay.Content.MAP, AirPlayPersistence.loadClusterContent(context))
        }
    }

    @Test fun bydAdbRoutingKeepsItsExistingInstrumentsDefault() {
        profile(VehicleIntegrationPolicy.BYD)
        AirPlayPersistence.saveAdbClusterEnabled(context, true)
        assertEquals(CarPlayClusterDisplay.Content.INSTRUMENTS, AirPlayPersistence.loadClusterContent(context))
        AirPlayPersistence.saveClusterContent(context, CarPlayClusterDisplay.Content.MAP)
        assertEquals(CarPlayClusterDisplay.Content.MAP, AirPlayPersistence.loadClusterContent(context))
    }
}
