package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ManualBluetoothStartupTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("diplay", Context.MODE_PRIVATE)

    @Before fun reset() { prefs.edit().clear().commit() }

    @Test fun ownerBetaClearsAnOldBluetoothOptInBeforeTheReceiverCanReadIt() {
        prefs.edit().putBoolean("connect_on_phone_bluetooth", true).putBoolean("auto_connect", true).commit()
        DiPlayPreferences.applyManualBluetoothStartupDefault(app, enabled = true)
        assertFalse(DiPlayPreferences.connectOnPhoneBluetooth(app))
        // The instruction concerned Bluetooth wake, not opening/starting the app manually.
        assertTrue(DiPlayPreferences.autoConnect(app))
    }

    @Test fun ordinaryBuildKeepsTheExistingPreference() {
        prefs.edit().putBoolean("connect_on_phone_bluetooth", true).commit()
        DiPlayPreferences.applyManualBluetoothStartupDefault(app, enabled = false)
        assertTrue(DiPlayPreferences.connectOnPhoneBluetooth(app))
    }

    @Test fun laterExplicitOptInIsNotOverwrittenOnEveryLaunch() {
        DiPlayPreferences.applyManualBluetoothStartupDefault(app, enabled = true)
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
        DiPlayPreferences.applyManualBluetoothStartupDefault(app, enabled = true)
        assertTrue(DiPlayPreferences.connectOnPhoneBluetooth(app))
    }
}
