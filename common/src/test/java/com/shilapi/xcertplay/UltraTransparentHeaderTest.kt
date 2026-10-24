package com.shilapi.xcertplay

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.host.R
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
class UltraTransparentHeaderTest {
    @Test fun wallpaperThemeKeepsTaskOpaqueAndAsksOnlyForSystemWallpaper() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_Xcertplay_UltraWallpaper)
        val attrs = activity.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent,
            android.R.attr.windowShowWallpaper))
        try {
            assertFalse(attrs.getBoolean(0, true))
            assertTrue(attrs.getBoolean(1, false))
        } finally { attrs.recycle(); controller.destroy() }
    }

    @Test fun compactFullAndMeasuredHeaderPreserveVideoAtLargeFont() {
        render("compact-large", 960, 600, 108, Locale.US)
        render("full-large", 2560, 1440, 180, Locale.US)
        render("c11-measured-large", 2560, 1176, 108, Locale.US)
        render("c11-ar-rtl-large", 2560, 1176, 108, Locale.forLanguageTag("ar-SA"))
    }

    @Test @Config(qualifiers = "xxxhdpi") fun highDensityHeaderAtLargeFont() {
        render("high-density-large", 2400, 1800, 450, Locale.US)
    }

    @Test fun narrowOrMissingTopSpaceCannotFallBackBelowOrBesideTheVideo() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val card = UltraBatteryCard(controller.get(), true)
            FrameLayout(controller.get()).addView(card.view)
            val snapshot = VehicleStatusSnapshot(160, null, 0.0, null, null, null, null)
            card.update(true, 1000, 800, RectF(0f, 0f, 1000f, 400f), emptyList(), snapshot)
            assertEquals(View.GONE, card.view.visibility)
            card.update(true, 100, 800, RectF(0f, 100f, 100f, 400f), emptyList(), snapshot)
            assertEquals(View.GONE, card.view.visibility)
            card.update(true, 1000, 800, RectF(0f, 100f, 1000f, 700f), emptyList(), null)
            assertEquals(View.GONE, card.view.visibility)
        } finally { controller.pause().stop().destroy() }
    }

    private fun render(name: String, width: Int, height: Int, top: Int, locale: Locale) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val previous = Configuration(activity.resources.configuration)
        try {
            activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
            val config = Configuration(previous).apply { fontScale = 1.5f; setLocale(locale); setLayoutDirection(locale) }
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            val picture = RectF(0f, top.toFloat(), width.toFloat(), (height - top).toFloat())
            val background = UltraHeaderBackground()
            val root = FrameLayout(activity).apply { this.background = background }
            val video = View(activity).apply { setBackgroundColor(Color.BLUE) }
            root.addView(video, FrameLayout.LayoutParams(width, height - top * 2).apply { topMargin = top })
            val card = UltraBatteryCard(activity, true)
            root.addView(card.view)
            // A synthetic OEM/status exclusion demonstrates that the alpha hole never crosses it.
            val exclusions = listOf(RectF(0f, 0f, width.toFloat(), 12f))
            card.update(true, width, height, picture, exclusions,
                VehicleStatusSnapshot(160, null, 0.0, null, null, null, null))
            assertEquals(View.VISIBLE, card.view.visibility)
            assertNull(card.view.background)
            assertFalse(card.view.text.contains('\n'))
            val zeroPercent = java.text.NumberFormat.getPercentInstance(locale).format(0.0)
            assertTrue(card.view.text.contains(zeroPercent))
            background.setHeader(RectF(0f, 0f, width.toFloat(), top.toFloat()), exclusions)
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            assertEquals(1, card.view.layout.lineCount)
            assertEquals(config.layoutDirection, card.view.layoutDirection)
            assertTrue(card.view.bottom <= picture.top)
            assertTrue(card.view.top >= 12)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                assertEquals(0, Color.alpha(bitmap.getPixel(1, top / 2)))
                assertEquals(Color.BLACK, bitmap.getPixel(1, 1))
                assertEquals(Color.BLACK, bitmap.getPixel(1, height - 1))
                val pixels = IntArray(width * (height - top * 2))
                bitmap.getPixels(pixels, 0, width, 0, top, width, height - top * 2)
                assertTrue("Video pixels/geometry changed", pixels.all { it == Color.BLUE })
                val output = File("build/reports/ultra-transparent-header").apply { mkdirs() }
                File(output, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                background.setHeader(null)
                bitmap.eraseColor(Color.TRANSPARENT)
                background.draw(Canvas(bitmap))
                assertEquals(Color.BLACK, bitmap.getPixel(1, top / 2))
            } finally { bitmap.recycle() }
        } finally {
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(previous, activity.resources.displayMetrics)
            controller.pause().stop().destroy()
        }
    }
}
