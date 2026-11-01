package com.shilapi.xcertplay

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class C11ClusterZoomControlsTest {
    private fun activity(c11: Boolean): DiPlayActivity {
        val screen = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        screen.setTheme(android.R.style.Theme_Material_NoActionBar)
        screen.applicationInfo.flags = screen.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        val info = screen.packageManager.getPackageInfo(screen.packageName, PackageManager.GET_META_DATA)
        info.applicationInfo!!.metaData = Bundle().apply {
            putString(VehicleIntegrationPolicy.METADATA_KEY, if (c11) VehicleIntegrationPolicy.LEAPMOTOR_C11 else VehicleIntegrationPolicy.BYD)
        }
        shadowOf(screen.packageManager).installPackage(info)
        return screen
    }

    private fun controls(screen: DiPlayActivity): LinearLayout = LinearLayout(screen).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(android.graphics.Color.rgb(15, 23, 42))
        layoutDirection = screen.resources.configuration.layoutDirection
        DiPlayActivity::class.java.getDeclaredMethod("clusterMapZoomControls", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(screen, this)
    }

    @Test fun sendsOneManualStepAndLeavesTheExistingMapGeometryAlone() {
        val screen = activity(true)
        try {
            val before = listOf(AirPlayPersistence.loadClusterMarkerXPercent(screen), AirPlayPersistence.loadClusterMarkerYPercent(screen),
                AirPlayPersistence.loadClusterMapScalePercent(screen))
            val requested = mutableListOf<Boolean>()
            screen.clusterZoomRequest = { requested += it; it }
            val card = controls(screen)
            assertTrue("Opening settings must not zoom automatically", requested.isEmpty())
            val buttons = views(card).filterIsInstance<TextView>().filter { it.isClickable }.toList()
            assertEquals(2, buttons.size)
            buttons[0].performClick()
            assertEquals(screen.getString(R.string.settings_cluster_zoom_requested), ShadowToast.getTextOfLatestToast())
            buttons[1].performClick()
            assertEquals(screen.getString(R.string.settings_cluster_zoom_unavailable), ShadowToast.getTextOfLatestToast())
            assertEquals(listOf(true, false), requested)
            assertEquals(before, listOf(AirPlayPersistence.loadClusterMarkerXPercent(screen), AirPlayPersistence.loadClusterMarkerYPercent(screen),
                AirPlayPersistence.loadClusterMapScalePercent(screen)))
        } finally { screen.finish() }
    }

    @Test fun otherVehicleProfilesDoNotGetTheC11Controls() {
        val screen = activity(false)
        try { assertEquals(0, controls(screen).childCount) } finally { screen.finish() }
    }

    @Test fun compactFullAndRtlScreenshotsWithLargeText() {
        val screen = activity(true)
        try {
            for ((name, widthDp, language) in listOf(Triple("compact-es-large", 320, "es"),
                    Triple("full-es-large", 960, "es"), Triple("compact-ar-rtl-large", 320, "ar"))) {
                val locale = Locale.forLanguageTag(language)
                val config = Configuration(screen.resources.configuration).apply {
                    fontScale = 1.5f; setLocale(locale); setLayoutDirection(locale)
                }
                @Suppress("DEPRECATION")
                screen.resources.updateConfiguration(config, screen.resources.displayMetrics)
                val card = controls(screen)
                val width = (widthDp * screen.resources.displayMetrics.density).toInt()
                card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                card.layout(0, 0, width, card.measuredHeight)
                views(card).filterIsInstance<TextView>().forEach { text ->
                    val layout = text.layout
                    if (layout != null) for (line in 0 until layout.lineCount)
                        assertEquals("No truncated zoom copy: $name", 0, layout.getEllipsisCount(line))
                }
                val image = Bitmap.createBitmap(width, card.measuredHeight, Bitmap.Config.ARGB_8888)
                try {
                    card.draw(Canvas(image))
                    val folder = File("build/reports/c11-cluster-zoom").apply { mkdirs() }
                    File(folder, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { image.recycle() }
            }
        } finally { screen.finish() }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
