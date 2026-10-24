package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compact/full and RTL screenshots of the C11 assistant controls at 1.5x text size. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class C11AssistantSettingsScreenshotsTest {
    @Test fun assistantControlsRenderAtLargeFontsInThreeLayouts() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        // The library test manifest lacks the mobile app's supportsRtl flag.
        activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        val info = activity.packageManager.getPackageInfo(activity.packageName, android.content.pm.PackageManager.GET_META_DATA)
        info.applicationInfo!!.metaData = android.os.Bundle().apply {
            putString(com.shilapi.xcertplay.vehicle.VehicleIntegrationPolicy.METADATA_KEY,
                com.shilapi.xcertplay.vehicle.VehicleIntegrationPolicy.LEAPMOTOR_C11)
        }
        org.robolectric.Shadows.shadowOf(activity.packageManager).installPackage(info)
        com.shilapi.xcertplay.vehicle.LeapmotorAssistantSettings.setEnabled(activity, true)
        try {
            for ((filename, widthDp, language) in listOf(
                Triple("compact-ja", 320, "ja-JP"),
                Triple("full-en", 960, "en-US"),
                Triple("compact-ar-rtl", 320, "ar-SA"),
            )) {
                val locale = Locale.forLanguageTag(language)
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 1.5f
                    setLocale(locale)
                    setLayoutDirection(locale)
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = if (language.startsWith("ar")) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                }
                DiPlayActivity::class.java.getDeclaredMethod("c11AssistantControls", LinearLayout::class.java)
                    .apply { isAccessible = true }.invoke(activity, row)
                assertTrue(allViews(row).filterIsInstance<TextView>().any {
                    it.text.toString().startsWith(activity.getString(R.string.settings_assistant_single))
                })
                val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                row.layout(0, 0, width, row.measuredHeight)
                val bitmap = Bitmap.createBitmap(width, row.measuredHeight, Bitmap.Config.ARGB_8888)
                try {
                    row.draw(Canvas(bitmap))
                    val directory = File("build/reports/c11-assistant-ui").apply { mkdirs() }
                    FileOutputStream(File(directory, "$filename.png")).use { output ->
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            activity.finish()
        }
    }

    private fun allViews(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(allViews(view.getChildAt(i)))
    }
}
