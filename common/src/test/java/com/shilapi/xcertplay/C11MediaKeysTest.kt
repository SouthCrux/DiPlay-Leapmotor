package com.shilapi.xcertplay

import android.content.pm.PackageManager
import android.media.session.MediaSession
import android.os.Bundle
import android.os.Looper
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.vehicle.VehicleIntegrationPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class C11MediaKeysTest {
    @Test fun bluetoothAudioLeavesTheNativeMediaSessionAndAvrcpInCharge() {
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveCarBluetoothAudio(context, true)
        val controller = mock(CarPlayController::class.java)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying(title = "Test track", playing = true))
        try {
            CarPlayMediaKeys.attach(context, controller)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(CarPlayMediaKeys.nativeBluetoothOwnsControls())
            assertNull(ReflectionHelpers.getField<MediaSession?>(CarPlayMediaKeys, "session"))
            CarPlayMediaKeys.onMediaAudioChanged(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(ReflectionHelpers.getField<MediaSession?>(CarPlayMediaKeys, "session"))
            CarPlayMediaKeys.onExternalMediaKey(com.shilapi.xcertplay.airplay.CarPlayMediaButton.PLAY_PAUSE)
            verify(controller, never()).sendMediaButton(anyInt())
        } finally {
            CarPlayMediaKeys.detach(controller)
            AirPlayPersistence.saveCarBluetoothAudio(context, false)
            assertFalse(CarPlayMediaKeys.nativeBluetoothOwnsControls())
        }
    }

    @Test fun phoneMetadataRegistersWheelControlsWithoutAnAudioDecoderAndDetachReleasesThem() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_META_DATA)
        info.applicationInfo!!.metaData = Bundle().apply {
            putString(VehicleIntegrationPolicy.METADATA_KEY, VehicleIntegrationPolicy.LEAPMOTOR_C11)
        }
        shadowOf(context.packageManager).installPackage(info)
        val controller = mock(CarPlayController::class.java)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying(title = "Test track", playing = false))
        try {
            CarPlayMediaKeys.attach(context, controller)
            shadowOf(Looper.getMainLooper()).idle()
            val session = ReflectionHelpers.getField<MediaSession?>(CarPlayMediaKeys, "session")
            assertNotNull("Paused phone music must remain controllable without decoder audio", session)
            assertTrue(session!!.isActive)
        } finally {
            CarPlayMediaKeys.detach(controller)
            assertNull(ReflectionHelpers.getField<MediaSession?>(CarPlayMediaKeys, "session"))
        }
    }
}
