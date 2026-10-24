package com.southcrux.c11diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorEvTelemetry
import org.json.JSONArray
import org.json.JSONObject

internal const val ENERGY_PERMISSION = "android.car.permission.CAR_ENERGY"

/** No VIN, serial, display names, accounts, location or network identifiers. */
internal object DiagnosticReport {
    fun baseline(context: Context): JSONObject {
        val manager = context.getSystemService(DisplayManager::class.java)
        val presentations = manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).map { it.displayId }.toSet()
        val displays = manager.displays.map { display ->
            JSONObject().put("logicalId", display.displayId).put("width", display.mode.physicalWidth)
                .put("height", display.mode.physicalHeight).put("refreshHz", display.mode.refreshRate)
                .put("flags", display.flags).put("state", display.state).put("rotation", display.rotation)
                .put("presentationCategory", display.displayId in presentations)
        }
        val permissions = listOf(ENERGY_PERMISSION, "android.car.permission.CAR_SPEED",
            "android.car.permission.CONTROL_CAR_CLIMATE", "com.leapmotor.carcontrol.CAR_CONTROL_SERVICE")
            .map { permission ->
                JSONObject().put("name", permission).put("requestedByThisApp", permission == ENERGY_PERMISSION)
                    .put("granted", context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
            }
        val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        return JSONObject().put("schemaVersion", 1).put("appVersion", appVersion)
            .put("package", context.packageName).put("sdk", Build.VERSION.SDK_INT)
            .put("automotiveFeature", context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
            .put("capturedAtElapsedNanos", SystemClock.elapsedRealtimeNanos())
            .put("permissions", JSONArray(permissions)).put("displays", JSONArray(displays))
            .put("carConnection", "NOT_ATTEMPTED").put("ev", JSONObject.NULL)
    }

    fun ev(telemetry: LeapmotorEvTelemetry): JSONArray = JSONArray(telemetry.fields.map { (property, result) ->
        JSONObject().put("property", property.name).put("id", property.id).put("state", result.state.name)
            .put("value", result.value ?: JSONObject.NULL)
            .put("timestampElapsedNanos", result.timestampNanos ?: JSONObject.NULL)
            .put("unit", when (property.name) {
                "ENERGY_WH", "NOMINAL_CAPACITY_WH" -> "Wh"
                "RANGE_METERS" -> "m"
                else -> "boolean"
            })
    })
}
