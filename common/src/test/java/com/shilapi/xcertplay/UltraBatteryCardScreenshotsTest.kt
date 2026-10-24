package com.shilapi.xcertplay

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import androidx.core.text.BidiFormatter
import androidx.core.text.TextDirectionHeuristicsCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UltraBatteryCardScreenshotsTest {
    @Test fun retainedExteriorWithoutBatteryAtLargeFont() {
        render("retained-exterior-compact-large", 1280, 900, 240, Locale.US,
            temperature = 13.0, onlyExterior = true, age = 600_001L)
        render("retained-exterior-full-large", 2560, 1440, 240, Locale.US,
            temperature = 13.0, onlyExterior = true, age = 600_001L)
        render("retained-exterior-ar-rtl-large", 1600, 1000, 260, Locale.forLanguageTag("ar-SA"),
            temperature = 13.0, onlyExterior = true, age = 600_001L)
    }
    @Test fun compactAndFullAtLargeFont() {
        render("compact-large", 960, 800, 220, Locale.US)
        render("full-large", 2560, 1440, 240, Locale.US)
    }

    @Test fun arabicRtlAtLargeFont() {
        render("compact-ar-rtl-large", 1280, 1000, 260, Locale.forLanguageTag("ar-SA"))
    }

    @Test fun exteriorCompactFullAndArabicAtLargeFont() {
        render("exterior-compact-large", 960, 800, 220, Locale.US, temperature = 13.0)
        render("exterior-full-large", 2560, 1440, 240, Locale.US, temperature = 13.0)
        render("exterior-ar-rtl-large", 1280, 1000, 260, Locale.forLanguageTag("ar-SA"), temperature = 13.0)
    }

    @Test @Config(qualifiers = "xxxhdpi") fun exteriorHighDensityAtLargeFont() {
        render("exterior-high-density-large", 2400, 1800, 450, Locale.US, temperature = 13.0)
    }

    @Test @Config(qualifiers = "mdpi") fun exteriorMeasuredC11AtLargeFont() {
        render("exterior-c11-mdpi-large", 2560, 1176, 108, Locale.US, temperature = 13.0)
    }

    @Test @Config(qualifiers = "xxxhdpi") fun highDensityAtLargeFont() {
        render("high-density-large", 2400, 1800, 450, Locale.US)
    }

    /** Measured C11 density160, app2560x1176, reference1920x720 fitted to2560x960. */
    @Test @Config(qualifiers = "mdpi") fun measuredC11LetterboxAtLargeFont() {
        render("c11-measured-mdpi-large", 2560, 1176, 108, Locale.US)
        render("c11-measured-mdpi-12-percent-large", 2560, 1176, 108, Locale.US, 12.0)
    }

    private fun render(filename: String, width: Int, height: Int, letterboxHeight: Int, locale: Locale,
        percent: Double = 100.0, temperature: Double? = null, onlyExterior: Boolean = false, age: Long? = null) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.applicationInfo.flags = activity.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        val original = Configuration(activity.resources.configuration)
        try {
            val configuration = Configuration(original).apply {
                fontScale = 1.5f
                setLocale(locale); setLayoutDirection(locale)
            }
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
            val parent = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
            val content = RectF(0f, letterboxHeight.toFloat(), width.toFloat(), (height - letterboxHeight).toFloat())
            val picture = View(activity).apply { setBackgroundColor(PICTURE_COLOR) }
            parent.addView(picture, FrameLayout.LayoutParams(width, height - 2 * letterboxHeight).apply { topMargin = letterboxHeight })
            val card = UltraBatteryCard(activity)
            parent.addView(card.view)
            card.update(true, width, height, content, emptyList(),
                if (onlyExterior) null else VehicleStatusSnapshot(160, null, percent, null, null, null, null), temperature, age)
            val measured = "$filename measured=${card.view.measuredWidth}x${card.view.measuredHeight} " +
                "letterbox=$letterboxHeight density=${activity.resources.displayMetrics.density} " +
                "scaledDensity=${activity.resources.displayMetrics.scaledDensity} textPx=${card.view.textSize} " +
                "padding=${card.view.compoundPaddingLeft},${card.view.compoundPaddingTop},${card.view.compoundPaddingRight},${card.view.compoundPaddingBottom}"
            assertEquals(measured, View.VISIBLE, card.view.visibility)
            val formatted = NumberFormat.getPercentInstance(locale).format(percent / 100.0)
            val title = listOfNotNull(if (onlyExterior) null else activity.getString(R.string.vehicle_field_battery),
                temperature?.let { activity.getString(R.string.vehicle_field_exterior_reported) },
                age?.let { activity.getString(R.string.vehicle_reading_received_minutes,
                    NumberFormat.getIntegerInstance(locale).format(it / 60_000L)) }).joinToString(" · ")
            val value = listOfNotNull(if (onlyExterior) null else formatted,
                temperature?.let { BidiFormatter.getInstance(locale).unicodeWrap(
                    NumberFormat.getNumberInstance(locale).format(it) + " °C", TextDirectionHeuristicsCompat.LTR) }).joinToString(" · ")
            assertEquals("$title\n$value", card.view.text.toString())
            assertEquals("$title · $value", card.view.contentDescription)
            parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, width, height)
            assertEquals(if (locale.language == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR,
                card.view.layoutDirection)
            assertEquals("$measured allocated=${card.view.width}x${card.view.height}", 2, card.view.layout.lineCount)
            val headingHeight = card.view.layout.getLineBottom(0) - card.view.layout.getLineTop(0)
            val valueHeight = card.view.layout.getLineBottom(1) - card.view.layout.getLineTop(1)
            assertTrue("$measured headingHeight=$headingHeight valueHeight=$valueHeight", headingHeight < valueHeight)
            for (line in 0 until card.view.layout.lineCount) {
                assertTrue("$measured line=$line lineWidth=${card.view.layout.getLineWidth(line)} available=${card.view.width - card.view.compoundPaddingLeft - card.view.compoundPaddingRight}",
                    card.view.layout.getLineWidth(line) <= card.view.width - card.view.compoundPaddingLeft - card.view.compoundPaddingRight)
            }
            assertTrue("$measured layoutHeight=${card.view.layout.height} allocatedHeight=${card.view.height}",
                card.view.layout.height <= card.view.height - card.view.compoundPaddingTop - card.view.compoundPaddingBottom)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                parent.draw(Canvas(bitmap))
                val pixels = IntArray(width * (height - 2 * letterboxHeight))
                bitmap.getPixels(pixels, 0, width, 0, letterboxHeight, width, height - 2 * letterboxHeight)
                assertTrue("Local indicator changed CarPlay pixels", pixels.all { it == PICTURE_COLOR })
                val directory = File("build/reports/ultra-battery-ui").apply { mkdirs() }
                File(directory, "$filename.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        } finally {
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(original, activity.resources.displayMetrics)
            controller.pause().stop().destroy()
        }
    }

    @Test @Config(qualifiers = "mdpi") fun batteryFillIsProportionalAndNeverInfersChargingOrWarningStates() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val card = UltraBatteryCard(context)
        val content = RectF(0f, 200f, 1000f, 600f)
        fun pixels(percent: Double, charging: Boolean? = null): IntArray {
            card.update(true, 1000, 800, content, emptyList(),
                VehicleStatusSnapshot(160, null, percent, null, null, null, charging))
            assertEquals(View.VISIBLE, card.view.visibility)
            val icon = card.view.compoundDrawablesRelative[0]
            val bitmap = Bitmap.createBitmap(icon.bounds.width(), icon.bounds.height(), Bitmap.Config.ARGB_8888)
            return try {
                icon.draw(Canvas(bitmap))
                IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
            } finally { bitmap.recycle() }
        }
        val empty = pixels(0.0)
        val low = pixels(12.0)
        val middle = pixels(75.0)
        val full = pixels(100.0)
        fun fill(values: IntArray) = values.count { it == 0xFF65D7DF.toInt() }
        assertEquals(0, fill(empty))
        assertTrue(fill(low) > 0)
        assertTrue(fill(middle) > fill(low))
        assertTrue(fill(full) > fill(middle))
        assertArrayEquals(low, pixels(12.0, true))
        assertArrayEquals(low, pixels(12.0, false))
        card.hide()
        assertEquals(View.GONE, card.view.visibility)
        assertEquals("", card.view.text.toString())
        val icon = card.view.compoundDrawablesRelative[0]
        val bitmap = Bitmap.createBitmap(icon.bounds.width(), icon.bounds.height(), Bitmap.Config.ARGB_8888)
        try {
            icon.draw(Canvas(bitmap))
            val values = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(values, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertEquals(0, fill(values))
        } finally { bitmap.recycle() }
    }

    private companion object { const val PICTURE_COLOR = 0xFF204060.toInt() }
}
