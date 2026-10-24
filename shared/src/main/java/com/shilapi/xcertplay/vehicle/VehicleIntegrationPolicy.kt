package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.content.pm.PackageManager

/** Explicit build choice, independent of Android version, installed packages or saved BYD switches. */
object VehicleIntegrationPolicy {
    const val METADATA_KEY = "com.shilapi.xcertplay.VEHICLE_INTEGRATION"
    const val LEAPMOTOR_C11 = "leapmotor-c11"
    const val BYD = "byd"

    fun isLeapmotorC11(context: Context): Boolean = runCatching {
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData?.getString(METADATA_KEY) == LEAPMOTOR_C11
    }.getOrDefault(false)

    /** Missing metadata preserves upstream sample apps; unknown explicit choices fail closed. */
    fun allowsByd(context: Context): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        val metadata = info.metaData
        if (metadata?.containsKey(METADATA_KEY) != true) true
        else metadata.getString(METADATA_KEY) == BYD
    }.getOrDefault(false)
}
