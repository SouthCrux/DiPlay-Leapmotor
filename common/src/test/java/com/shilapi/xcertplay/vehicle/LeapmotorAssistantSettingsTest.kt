package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LeapmotorAssistantSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun profile(name: String) {
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
        info.applicationInfo!!.metaData = Bundle().apply { putString(VehicleIntegrationPolicy.METADATA_KEY, name) }
        shadowOf(context.packageManager).installPackage(info)
    }

    @Test fun activationIsOptInAndC11OnlyEvenWithSavedPreference() {
        profile(VehicleIntegrationPolicy.LEAPMOTOR_C11)
        assertFalse(LeapmotorAssistantSettings.enabled(context))
        LeapmotorAssistantSettings.setEnabled(context, true)
        assertTrue(LeapmotorAssistantSettings.enabled(context))
        profile(VehicleIntegrationPolicy.BYD)
        assertFalse(LeapmotorAssistantSettings.enabled(context))
    }

    @Test fun gesturesKeepIndependentChoicesAndUnknownValuesDoNothing() {
        assertEquals(LeapmotorAssistantSettings.Action.SYSTEM, LeapmotorAssistantSettings.action(context, false))
        assertEquals(LeapmotorAssistantSettings.Action.CHATGPT, LeapmotorAssistantSettings.action(context, true))
        LeapmotorAssistantSettings.setAction(context, false, LeapmotorAssistantSettings.Action.GEMINI)
        LeapmotorAssistantSettings.setAction(context, true, LeapmotorAssistantSettings.Action.CLAUDE)
        assertEquals(LeapmotorAssistantSettings.Action.GEMINI, LeapmotorAssistantSettings.action(context, false))
        assertEquals(LeapmotorAssistantSettings.Action.CLAUDE, LeapmotorAssistantSettings.action(context, true))
        context.getSharedPreferences("diplay_c11_voice_button", Context.MODE_PRIVATE).edit()
            .putString("single", "UNRECOGNIZED").commit()
        assertEquals(LeapmotorAssistantSettings.Action.NONE, LeapmotorAssistantSettings.action(context, false))
    }
}
