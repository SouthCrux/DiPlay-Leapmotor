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
import com.shilapi.xcertplay.media.CarPlayVideoLayout
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
class C11CompactVehicleHeaderTest {
    @Test fun compactFullAndRtlAtLargeFont() {
        render("compact-es-large", 960, 600, Locale.forLanguageTag("es"))
        render("full-es-large", 2560, 1176, Locale.forLanguageTag("es"))
        render("rtl-ar-large", 1280, 800, Locale.forLanguageTag("ar"))
    }

    private fun render(name: String, width: Int, height: Int, locale: Locale) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val original = Configuration(activity.resources.configuration)
        try {
            activity.applicationInfo.flags = activity.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
            val config = Configuration(original).apply { fontScale = 1.5f; setLocale(locale); setLayoutDirection(locale) }
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            val header = (48 * activity.resources.displayMetrics.density * config.fontScale).toInt()
            val fitted = CarPlayVideoLayout.fitBelowHeader(width, height, width, height, header)
            val content = RectF(fitted.left, fitted.top, fitted.left + fitted.width, fitted.top + fitted.height)
            val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
            root.addView(View(activity).apply { setBackgroundColor(Color.rgb(29, 54, 78)) },
                FrameLayout.LayoutParams(fitted.width.toInt(), fitted.height.toInt()).apply {
                    leftMargin = fitted.left.toInt(); topMargin = fitted.top.toInt()
                })
            val card = UltraBatteryCard(activity, compactHeader = true)
            root.addView(card.view)
            val snapshot = VehicleStatusSnapshot(160, null, 60.0, null, null, null, null)
            card.update(true, width, height, content, emptyList(), snapshot, 15.5, 600_001L)
            assertEquals(View.VISIBLE, card.view.visibility)
            assertFalse(card.view.text.contains('\n'))
            assertFalse(card.view.text.contains("recibido"))
            assertFalse(card.view.text.contains("Estado de"))
            assertNull(card.view.background)
            val text = card.view.text.toString()
            card.update(true, width, height, content, emptyList(), snapshot, 15.5, 1_200_001L)
            assertEquals(text, card.view.text.toString())
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            assertEquals(1, card.view.layout.lineCount)
            assertTrue(card.view.bottom <= content.top)
            assertEquals(config.layoutDirection, card.view.layoutDirection)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val output = File("build/reports/c11-compact-header").apply { mkdirs() }
                File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        } finally {
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(original, activity.resources.displayMetrics)
            controller.pause().stop().destroy()
        }
    }
}
