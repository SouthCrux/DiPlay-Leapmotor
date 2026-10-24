package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.RectF
import android.os.LocaleList
import android.util.DisplayMetrics
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import com.shilapi.xcertplay.host.R
import java.text.NumberFormat
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class UltraBatteryCardTest {
    private fun snapshot(percent: Double) = VehicleStatusSnapshot(160, null, percent, null, null, null, null)

    @Test fun temperatureDoesNotDependOnBatteryAndOldReceiptIsLabelled() {
        val card = UltraBatteryCard(RuntimeEnvironment.getApplication())
        val content = RectF(0f, 200f, 1800f, 800f)
        card.update(true, 1800, 1000, content, emptyList(), null, 13.0, 600_001L)
        assertEquals(View.VISIBLE, card.view.visibility)
        assertTrue(card.view.text.toString().contains("13 °C"))
        assertTrue(card.view.text.toString().contains("10"))
        assertNull(card.view.compoundDrawablesRelative[0])
        card.update(true, 1800, 1000, content, emptyList(), snapshot(75.0), 13.0, 1)
        assertNotNull(card.view.compoundDrawablesRelative[0])
        card.update(true, 1800, 1000, content, emptyList(), null, null)
        assertEquals(View.GONE, card.view.visibility)
    }

    @Test fun reportedExteriorAppearsWithBatteryAndWithdrawsWithoutHidingTheBattery() {
        val card = UltraBatteryCard(RuntimeEnvironment.getApplication())
        val content = RectF(0f, 200f, 1400f, 600f)
        card.update(true, 1400, 800, content, emptyList(), snapshot(75.0), 13.0)
        assertEquals(View.VISIBLE, card.view.visibility)
        assertTrue(card.view.text.toString().contains(card.view.context.getString(R.string.vehicle_field_exterior_reported)))
        assertTrue(card.view.text.toString().contains("13 °C"))
        card.update(true, 1400, 800, content, emptyList(), snapshot(75.0), null)
        assertEquals(View.VISIBLE, card.view.visibility)
        assertFalse(card.view.text.toString().contains("°C"))
    }

    @Test fun cardOnlyFitsVerifiedLetterboxAndNeverMovesOrCoversTheVideo() {
        val content = RectF(0f, 108f, 2560f, 1068f)
        val original = RectF(content)
        val rectangle = checkNotNull(UltraBatteryCardPlacement.place(2560, 1176, content, emptyList(), 180, 60, 8))
        assertEquals(original, content)
        assertFalse(RectF.intersects(RectF(rectangle), content))
        assertTrue(Rect(0, 0, 2560, 1176).contains(rectangle))
        assertNull(UltraBatteryCardPlacement.place(2560, 1176, RectF(0f, 0f, 2560f, 1176f), emptyList(), 180, 60, 8))
    }

    @Test fun controlsCutOutOfLetterboxAndAllOccupiedSpaceHidesTheCard() {
        val content = RectF(0f, 100f, 1000f, 500f)
        val controls = listOf(RectF(0f, 0f, 700f, 100f), RectF(0f, 500f, 1000f, 600f))
        val rectangle = checkNotNull(UltraBatteryCardPlacement.place(1000, 600, content, controls, 160, 50, 8))
        controls.forEach { assertFalse(RectF.intersects(RectF(rectangle), it)) }
        assertFalse(RectF.intersects(RectF(rectangle), content))
        assertNull(UltraBatteryCardPlacement.place(1000, 600, content,
            listOf(RectF(0f, 0f, 1000f, 600f)), 160, 50, 8))
    }

    @Test fun unknownOrInvalidGeometryCannotBeTreatedAsFreeSpace() {
        for (content in listOf(null, RectF(0f, 0f, 0f, 0f), RectF(-1f, 10f, 100f, 90f),
            RectF(0f, Float.NaN, 100f, 90f), RectF(0f, 10f, 101f, 90f))) {
            assertNull(UltraBatteryCardPlacement.place(100, 100, content, emptyList(), 50, 20, 2))
        }
        assertNull(UltraBatteryCardPlacement.place(100, 100, RectF(0f, 30f, 100f, 70f),
            listOf(RectF(Float.NaN, 0f, 10f, 10f)), 50, 20, 2))
    }

    @Test fun missingSourceAndEligibilityClearPriorValueWhileZeroRemainsValid() {
        val card = UltraBatteryCard(RuntimeEnvironment.getApplication())
        val content = RectF(0f, 150f, 1000f, 450f)
        card.update(true, 1000, 600, content, emptyList(), snapshot(0.0))
        assertEquals(View.VISIBLE, card.view.visibility)
        assertEquals(card.view.context.getString(R.string.vehicle_field_battery) + "\n" + NumberFormat.getPercentInstance(card.view.resources.configuration.locales[0]).format(0.0), card.view.text.toString())
        card.update(true, 1000, 600, content, emptyList(), null)
        assertEquals(View.GONE, card.view.visibility)
        assertEquals("", card.view.text.toString())
        assertNull(card.view.contentDescription)
        card.update(true, 1000, 600, content, emptyList(), snapshot(75.0))
        card.update(false, 1000, 600, content, emptyList(), snapshot(75.0))
        assertEquals(View.GONE, card.view.visibility)
        card.update(true, 1000, 600, content, emptyList(), snapshot(Double.NaN))
        assertEquals(View.GONE, card.view.visibility)
    }

    @Test fun largeFontAndArabicPercentFitMeasuredCardOrHideWithoutClipping() {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration).apply {
            fontScale = 2f
            setLocales(LocaleList(Locale("ar")))
        }
        val context = app.createConfigurationContext(config)
        val card = UltraBatteryCard(context)
        val content = RectF(0f, 300f, 1000f, 700f)
        card.update(true, 1000, 1000, content, emptyList(), snapshot(100.0))
        assertEquals(View.VISIBLE, card.view.visibility)
        assertEquals(card.view.context.getString(R.string.vehicle_field_battery) + "\n" + NumberFormat.getPercentInstance(Locale("ar")).format(1.0), card.view.text.toString())
        val parameters = card.view.layoutParams as FrameLayout.LayoutParams
        assertTrue(parameters.width >= card.view.measuredWidth)
        assertTrue(parameters.height >= card.view.measuredHeight)
        assertFalse(RectF.intersects(RectF(parameters.leftMargin.toFloat(), parameters.topMargin.toFloat(),
            (parameters.leftMargin + parameters.width).toFloat(), (parameters.topMargin + parameters.height).toFloat()), content))
        card.update(true, 100, 100, RectF(0f, 25f, 100f, 75f), emptyList(), snapshot(100.0))
        assertEquals(View.GONE, card.view.visibility)
        assertEquals("", card.view.text.toString())
    }

    @Test @Config(qualifiers = "xxxhdpi") fun highDensityNeedsActualRoomForMeasuredText() {
        val card = UltraBatteryCard(RuntimeEnvironment.getApplication())
        card.update(true, 2400, 1800, RectF(0f, 300f, 2400f, 1500f), emptyList(), snapshot(75.0))
        assertEquals(View.VISIBLE, card.view.visibility)
        card.update(true, 2400, 1800, RectF(0f, 20f, 2400f, 1780f), emptyList(), snapshot(75.0))
        assertEquals(View.GONE, card.view.visibility)
    }

    @Test fun sameViewRefreshesFontDensityAndLocaleWithoutRecreatingItsSourceOrTimer() {
        val context = RuntimeEnvironment.getApplication()
        val originalFlags = context.applicationInfo.flags
        context.applicationInfo.flags = originalFlags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        val originalConfiguration = Configuration(context.resources.configuration)
        val originalMetrics = DisplayMetrics().apply { setTo(context.resources.displayMetrics) }
        try {
            val card = UltraBatteryCard(context)
            val picture = RectF(0f, 600f, 2400f, 1200f)
            card.update(true, 2400, 1800, picture, emptyList(), snapshot(100.0))
            val originalTextSize = card.view.textSize
            val originalWidth = card.view.measuredWidth
            val originalPadding = card.view.paddingLeft
            val changedConfiguration = Configuration(originalConfiguration).apply {
                fontScale = 1.5f
                densityDpi = originalMetrics.densityDpi * 2
                setLocales(LocaleList(Locale("ar")))
                setLayoutDirection(Locale("ar"))
            }
            val changedMetrics = DisplayMetrics().apply {
                setTo(originalMetrics)
                densityDpi *= 2
                density *= 2
                scaledDensity = density * 1.5f
            }
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(changedConfiguration, changedMetrics)
            card.update(true, 2400, 1800, picture, emptyList(), snapshot(100.0))
            assertEquals(View.VISIBLE, card.view.visibility)
            assertTrue(card.view.textSize > originalTextSize)
            assertTrue(card.view.measuredWidth > originalWidth)
            assertTrue(card.view.paddingLeft > originalPadding)
            assertEquals(View.LAYOUT_DIRECTION_RTL, card.view.layoutDirection)
            assertEquals(card.view.context.getString(R.string.vehicle_field_battery) + "\n" + NumberFormat.getPercentInstance(Locale("ar")).format(1.0), card.view.text.toString())
        } finally {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(originalConfiguration, originalMetrics)
            context.applicationInfo.flags = originalFlags
        }
    }

    @Test fun unchangedTickReusesLayoutParametersAndDoesNotRequestAnotherLayout() {
        val card = UltraBatteryCard(RuntimeEnvironment.getApplication())
        val content = RectF(0f, 200f, 1000f, 600f)
        card.update(true, 1000, 800, content, emptyList(), snapshot(75.0))
        val parameters = card.view.layoutParams
        card.view.layout(0, 0, card.view.measuredWidth, card.view.measuredHeight)
        assertFalse(card.view.isLayoutRequested)
        card.update(true, 1000, 800, content, emptyList(), snapshot(75.0))
        assertSame(parameters, card.view.layoutParams)
        assertFalse(card.view.isLayoutRequested)
        assertFalse(card.view.isClickable)
        assertFalse(card.view.isLongClickable)
        assertFalse(card.view.isFocusable)
    }
}
