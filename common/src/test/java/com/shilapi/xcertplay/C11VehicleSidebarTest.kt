package com.shilapi.xcertplay

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorQnxTireState.Wheel
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
class C11VehicleSidebarTest {
    @Test fun configuredGateStaysVisibleWhenCheckingOrDisconnected() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val sidebar = C11VehicleSidebar(controller.get())
            val gate = org.robolectric.util.ReflectionHelpers.getField<android.widget.TextView>(sidebar, "gate")
            val block = gate.parent as View
            assertEquals(View.GONE, block.visibility)
            for (state in com.shilapi.xcertplay.vehicle.C11RefossGate.Status.entries) {
                sidebar.update(null, null, gateStatus = state)
                assertEquals("Configured gate must not disappear: $state", View.VISIBLE, block.visibility)
                assertTrue(gate.text.isNotEmpty())
                if (state != com.shilapi.xcertplay.vehicle.C11RefossGate.Status.READY)
                    assertNotEquals(controller.get().getString(com.shilapi.xcertplay.host.R.string.c11_gate_double_click), gate.text.toString())
            }
            sidebar.update(null, null)
            assertEquals(View.GONE, block.visibility)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun reservedViewportFillsTheHeightAndNeverReceivesSidebarTouches() {
        val viewport = CarPlayVideoLayout.fit(1760, 1176, 1760, 1176)
        assertEquals(0f, viewport.top, 0.01f)
        assertEquals(1176f, viewport.height, 0.01f)
        assertFalse(viewport.contains(1800f, 400f))
    }

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
            val config = Configuration(original).apply { fontScale = 1.5f; setLocale(locale); setLayoutDirection(locale) }
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            val sidebarWidth = C11VehicleSidebar.widthPixels(activity.resources, width)
            assertTrue(sidebarWidth <= width / 4)
            val root = FrameLayout(activity).apply { setBackgroundColor(android.graphics.Color.rgb(29, 54, 78)) }
            val sidebar = C11VehicleSidebar(activity).apply { visibility = View.VISIBLE }
            root.addView(sidebar, FrameLayout.LayoutParams(sidebarWidth, -1, Gravity.RIGHT))
            val pressures = Wheel.entries.zip(listOf(233, 228, 229, 231)).toMap()
            val temperatures = Wheel.entries.zip(listOf(19, 20, 21, 22)).toMap()
            sidebar.update(VehicleStatusSnapshot(160, null, 60.0, null, null, null, null), 15.5, pressures, temperatures, 13.76,
                gateStatus = com.shilapi.xcertplay.vehicle.C11RefossGate.Status.UNREACHABLE)
            val consumption = org.robolectric.util.ReflectionHelpers.getField<android.widget.TextView>(sidebar, "consumption")
            assertEquals(View.VISIBLE, consumption.visibility)
            assertTrue(consumption.text.toString().contains("kWh/100 km"))
            val battery = org.robolectric.util.ReflectionHelpers.getField<android.widget.TextView>(sidebar, "battery")
            val range = org.robolectric.util.ReflectionHelpers.getField<android.widget.TextView>(sidebar, "range")
            val tyres = org.robolectric.util.ReflectionHelpers.getField<android.widget.TextView>(sidebar, "tyres")
            assertTrue(tyres.text.toString().contains("233"))
            assertTrue(tyres.contentDescription.toString().contains("229"))
            assertTrue(tyres.text.toString().contains("19 °C"))
            assertTrue(tyres.text.toString().contains("22 °C"))
            assertEquals("Both axle labels must carry the pressure unit", 2,
                Regex("\\(kPa\\)").findAll(tyres.text.toString()).count())
            assertFalse("No detached pressure unit at the bottom", tyres.text.toString().trim().endsWith("kPa"))
            assertTrue(tyres.contentDescription.toString().contains("21 °C"))
            assertTrue(range.text.toString().contains("160 km"))
            assertNotNull(range.compoundDrawablesRelative[0])
            sidebar.update(null, 15.5)
            assertEquals(View.GONE, consumption.visibility)
            assertEquals("", consumption.text.toString())
            sidebar.update(null, null, currentTripConsumption = 0.0)
            assertEquals(View.VISIBLE, consumption.visibility)
            assertTrue(consumption.text.toString().contains("kWh/100 km"))
            sidebar.update(null, null, currentTripConsumption = -1.0)
            assertEquals(View.GONE, consumption.visibility)
            assertEquals("", consumption.text.toString())
            sidebar.update(null, null, currentTripConsumption = Double.NaN)
            assertEquals(View.GONE, consumption.visibility)
            assertEquals("Lost vehicle data must hide the range", View.GONE, range.visibility)
            assertEquals("Lost vehicle data must clear the range", "", range.text.toString())
            assertFalse(battery.text.toString().contains("160"))
            assertFalse("Lost tyre data must clear the pressure", tyres.text.toString().contains("233"))
            assertFalse("Lost tyre data must clear the temperature", tyres.text.toString().contains("°C"))
            sidebar.update(null, null, pressures, mapOf(Wheel.FRONT_LEFT to -1, Wheel.REAR_RIGHT to 151))
            assertTrue("Subzero tyre temperatures are valid", tyres.text.toString().contains("-1 °C"))
            assertFalse("Out-of-range readings must not be shown", tyres.text.toString().contains("151"))
            assertTrue("A missing temperature must not become zero", tyres.text.toString().contains("— °C"))
            sidebar.update(null, null, pressures)
            assertTrue("Pressure remains available without temperature", tyres.text.toString().contains("233"))
            assertFalse("Losing temperature must remove its old value", tyres.text.toString().contains("°C"))
            sidebar.update(VehicleStatusSnapshot(160, null, 60.0, null, null, null, null), 15.5, pressures, temperatures, 13.76)
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            assertEquals(width, sidebar.right)
            assertEquals(0, sidebar.top)
            assertEquals(height, sidebar.height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val output = File("build/reports/c11-sidebar").apply { mkdirs() }
                File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                sidebar.update(VehicleStatusSnapshot(160, null, 60.0, null, null, null, null), 15.5, pressures, temperatures, 13.76,
                    gateStatus = com.shilapi.xcertplay.vehicle.C11RefossGate.Status.UNREACHABLE)
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                sidebar.scrollTo(0, sidebar.getChildAt(0).height)
                root.draw(Canvas(bitmap))
                File(output, "$name-gate.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        } finally {
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(original, activity.resources.displayMetrics)
            controller.pause().stop().destroy()
        }
    }
}
