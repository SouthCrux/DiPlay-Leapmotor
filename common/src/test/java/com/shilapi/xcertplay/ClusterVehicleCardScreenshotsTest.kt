package com.shilapi.xcertplay

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterVehicleCardScreenshotsTest {
    @Test fun contextualBatteryAtLargeFontWithoutOemInstrumentReplacement() {
        render("compact-large", 360, 360, Locale.US)
        render("full-large", 720, 720, Locale.US)
        render("full-ar-rtl-large", 720, 720, Locale.forLanguageTag("ar-SA"))
    }

    private fun render(name: String, width: Int, height: Int, locale: Locale) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.applicationInfo.flags = activity.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        val original = Configuration(activity.resources.configuration)
        try {
            val config = Configuration(original).apply {
                fontScale = 1.5f
                setLocale(locale)
                setLayoutDirection(locale)
            }
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            val card = ClusterVehicleCardView(activity)
            card.setSnapshot(VehicleStatusSnapshot(83, null, 12.0, null, null, null, null))
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            card.layout(0, 0, width, height)
            assertEquals(View.VISIBLE, card.visibility)
            assertEquals(3, card.reading.layout.lineCount)
            for (line in 0 until card.reading.layout.lineCount) {
                assertTrue("$name line $line overflows", card.reading.layout.getLineWidth(line) <= card.reading.width)
            }
            assertTrue(card.reading.layout.height <= card.reading.height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                card.draw(Canvas(bitmap))
                val directory = File("build/reports/ultra-contextual-cockpit").apply { mkdirs() }
                File(directory, "$name.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
            card.setSnapshot(null)
            assertEquals(View.GONE, card.visibility)
            assertEquals("", card.reading.text.toString())
            assertNull(card.contentDescription)
        } finally {
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(original, activity.resources.displayMetrics)
            controller.pause().stop().destroy()
        }
    }

    @Test fun rejectsMalformedValuesAndPreservesRealZeroReadings() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val card = ClusterVehicleCardView(controller.get())
            val valid = VehicleStatusSnapshot(0, null, 0.0, null, null, null, null)
            card.setSnapshot(valid)
            assertEquals(View.VISIBLE, card.visibility)
            for (bad in listOf(valid.copy(batteryPercent = Double.NaN), valid.copy(batteryPercent = 101.0),
                valid.copy(batteryPercent = -1.0), valid.copy(rangeKm = -1), valid.copy(rangeKm = 65536))) {
                card.setSnapshot(bad)
                assertEquals(View.GONE, card.visibility)
                assertEquals("", card.reading.text.toString())
            }
        } finally { controller.pause().stop().destroy() }
    }
}
