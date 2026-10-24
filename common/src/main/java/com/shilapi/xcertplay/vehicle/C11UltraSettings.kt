package com.shilapi.xcertplay.vehicle

import android.content.Context

/** A handshake-only experimental opt-in, independent of telemetry; changing it never reconnects the running session. */
object C11UltraSettings {
    /** Names, geometry and density from the inspected phone package, not a C11 theme. */
    fun referenceThemeBinding(context: Context): com.shilapi.xcertplay.airplay.UltraThemeBinding? =
        if (enabled(context)) com.shilapi.xcertplay.airplay.UltraThemeBinding(
            com.shilapi.xcertplay.transport.Iap2ThemeAsset("44C9FA31EC", 985, "19.0"),
            com.shilapi.xcertplay.airplay.UltraThemeDisplay("Center_Display", 1920, 720, 157),
            com.shilapi.xcertplay.airplay.UltraThemeDisplay("Cluster_Display", 1920, 720, 157),
        ) else null

    fun configure(context: Context, config: com.shilapi.xcertplay.airplay.AirPlayConfig):
        com.shilapi.xcertplay.airplay.AirPlayConfig = referenceThemeBinding(context)?.configure(config) ?: config

    fun enabled(context: Context): Boolean = (android.os.Build.VERSION.SDK_INT >= 28 && VehicleIntegrationPolicy.isLeapmotorC11(context)) &&
        context.getSharedPreferences("c11_ultra", Context.MODE_PRIVATE).getBoolean("handshake", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("c11_ultra", Context.MODE_PRIVATE).edit()
            .putBoolean("handshake", enabled).apply()
    }
}
