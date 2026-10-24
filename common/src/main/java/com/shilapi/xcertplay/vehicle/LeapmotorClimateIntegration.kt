package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import com.shilapi.xcertplay.airplay.UltraClimateSessionRegistry
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorClimateBackendFactory
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorClimateVerification
import com.shilapi.xcertplay.vehicle.leapmotor.ReflectiveLeapmotorClimateAccess
import java.io.Closeable

/** App-scoped handoff only. Registration does not obtain or confer an OEM permission. */
object LeapmotorClimateIntegration {
    internal val sessions = UltraClimateSessionRegistry()

    /**
     * A future authorized broker supplies separately owned, already refreshed backends.
     * Never register simulated data in the car. Closing the registration revokes all sessions.
     */
    fun registerAuthorizedBroker(factory: UltraClimateSessionRegistry.Factory): Closeable = sessions.register(factory)

    data class OwnedCarPropertyManager(val manager: Any, val owner: Closeable)

    /**
     * Explicit development integration for a legitimately authorized CarProperty manager. Does not
     * request/grant CONTROL_CAR_CLIMATE or load extracted OEM jars. The factory is never called on
     * main, outside the driver's existing Ultra opt-in, or before an actual permission check.
     * Verification must describe measured physical seats and increments on this exact firmware.
     * Each acquisition owns a separate manager; closing the registration closes all current owners.
     */
    fun registerAuthorizedCarPropertyBroker(
        context: Context,
        verification: LeapmotorClimateVerification,
        openManager: () -> OwnedCarPropertyManager?,
    ): Closeable {
        val app = context.applicationContext
        val factory = LeapmotorClimateBackendFactory(verification, { Build.FINGERPRINT }, {
            C11UltraSettings.enabled(app) &&
                app.checkSelfPermission(CONTROL_CAR_CLIMATE) == PackageManager.PERMISSION_GRANTED
        }, {
            if (Looper.myLooper() == Looper.getMainLooper()) null
            else openManager()?.let { owned ->
                LeapmotorClimateBackendFactory.OwnedAccess(
                    ReflectiveLeapmotorClimateAccess(owned.manager), owned.owner)
            }
        })
        return registerAuthorizedBroker(factory)
    }

    private const val CONTROL_CAR_CLIMATE = "android.car.permission.CONTROL_CAR_CLIMATE"
}
