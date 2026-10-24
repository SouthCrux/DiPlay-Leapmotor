package com.shilapi.xcertplay.vehicle

import android.content.Context
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** OEM output only. Decoding the phone's on-screen turn card does not belong to an OEM backend. */
interface VehicleNavigationOutput {
    fun start(context: Context) {}
    fun setClusterStreamControl(control: (Boolean) -> Unit) {}
    fun clearClusterStreamControl(control: (Boolean) -> Unit) {}
    fun carPlaySessionStarted() {}
    fun onFrame(frame: Iap2Frame) {}
    fun endNow(preserveTurnOverlay: Boolean = false) {}

    companion object {
        /** No vendor worker, service lookup or command; C11 output awaits verified interfaces. */
        val NONE: VehicleNavigationOutput = object : VehicleNavigationOutput {}

        fun forContext(context: Context): VehicleNavigationOutput =
            if (VehicleIntegrationPolicy.allowsByd(context)) BydNavigationOutputs else NONE
    }
}
