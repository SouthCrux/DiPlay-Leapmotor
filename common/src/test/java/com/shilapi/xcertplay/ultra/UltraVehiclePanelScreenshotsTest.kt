package com.shilapi.xcertplay.ultra

import android.content.res.Configuration
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UltraVehiclePanelScreenshotsTest {
    @Test fun vehicleSummaryAndDetailAtLargeFontWithRtlAndMissingData() {
        render("compact-large", 825, 360, Locale.US)
        render("full-large", 1650, 720, Locale.US)
        render("full-ar-rtl-large", 1650, 720, Locale.forLanguageTag("ar-SA"))
        render("full-unavailable-large", 1650, 720, Locale.US, available = false)
    }

    private fun render(name: String, width: Int, height: Int, locale: Locale, available: Boolean = true) {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration).apply {
            setLocale(locale); setLayoutDirection(locale); fontScale = 1.5f
        }
        val context = app.createConfigurationContext(config)
        val provider = VehicleStatusProvider {
            if (available) VehicleStatusSnapshot(83, null, 12.0, null, null, null, null) else null
        }
        UltraVehiclePanelSession(context, provider).use { session ->
            val summary = checkNotNull(session.nextFrame(width, height))
            summary.use {
                save("$name-summary", it, width, height)
                if (available) {
                    val button = it.hitRegions.single()
                    assertEquals(UltraVehiclePanelSession.Action.BATTERY_DETAILS, button.action)
                    assertTrue(session.tap(it, (button.left + button.right) / 2, (button.top + button.bottom) / 2))
                } else assertTrue(it.hitRegions.isEmpty())
            }
            if (available) checkNotNull(session.nextFrame(width, height)).use { details ->
                assertEquals(UltraVehiclePanelSession.Page.BATTERY_DETAILS, details.page)
                save("$name-detail", details, width, height)
            }
        }
    }

    private fun save(name: String, frame: UltraVehiclePanelSession.Frame, width: Int, height: Int) {
        assertEquals(width, frame.bitmap.width)
        assertEquals(height, frame.bitmap.height)
        assertTrue(frame.contentDescription.isNotBlank())
        frame.hitRegions.forEach {
            assertTrue(it.left >= 0 && it.top >= 0 && it.right <= width && it.bottom <= height)
            assertTrue(it.left < it.right && it.top < it.bottom)
        }
        val directory = File("build/reports/ultra-vehicle-panel").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            assertTrue(frame.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
