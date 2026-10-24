package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.vehicle.VehicleIntegrationPolicy
import java.io.ByteArrayOutputStream

/** Defaults only; a driver's custom car-button image and name still take precedence. */
internal object CarButtonBranding {
    fun label(context: Context): String =
        if (VehicleIntegrationPolicy.isLeapmotorC11(context)) "Leapmotor" else AirPlayPersistence.DEFAULT_OEM_LABEL

    fun iconBytes(context: Context): ByteArray {
        if (!VehicleIntegrationPolicy.isLeapmotorC11(context)) {
            return context.resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }
        }
        val size = AirPlayIconSizing.MAX_PIXELS
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val drawable = requireNotNull(context.getDrawable(R.drawable.ic_leapmotor_car_home))
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
