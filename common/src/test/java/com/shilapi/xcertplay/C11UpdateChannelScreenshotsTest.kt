package com.shilapi.xcertplay

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.vehicle.VehicleIntegrationPolicy
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class C11UpdateChannelScreenshotsTest {
    @Test fun customChannelExplainsUpdatesWithoutOfferingTheOfficialInstaller() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val info = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_META_DATA)
        info.applicationInfo!!.metaData = Bundle().apply {
            putString(VehicleIntegrationPolicy.METADATA_KEY, VehicleIntegrationPolicy.LEAPMOTOR_C11)
        }
        shadowOf(activity.packageManager).installPackage(info)
        try {
            for ((name, widthDp, language) in listOf(
                Triple("compact-es-large", 320, "es"), Triple("full-es-large", 960, "es"),
                Triple("rtl-ar-large", 320, "ar"))) {
                val locale = Locale.forLanguageTag(language)
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 1.5f; setLocale(locale); setLayoutDirection(locale)
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                val row = DiPlayActivity::class.java.getDeclaredMethod("updateRow")
                    .apply { isAccessible = true }.invoke(activity) as View
                row.layoutDirection = if (language == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                val texts = descendants(row).filterIsInstance<TextView>().map { it.text.toString() }.toList()
                assertTrue(texts.contains(activity.getString(R.string.settings_c11_update_channel)))
                assertFalse(texts.contains(activity.getString(R.string.update_check)))
                assertFalse(texts.contains(activity.getString(R.string.update_download)))
                val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                row.layout(0, 0, width, row.measuredHeight)
                val bitmap = Bitmap.createBitmap(width, row.measuredHeight, Bitmap.Config.ARGB_8888)
                try {
                    row.draw(Canvas(bitmap))
                    val directory = File("build/reports/c11-update-channel").apply { mkdirs() }
                    File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
        } finally { activity.finish() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
