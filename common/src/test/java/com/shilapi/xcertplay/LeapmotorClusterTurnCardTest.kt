package com.shilapi.xcertplay

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LeapmotorClusterTurnCardTest {
    @Test fun theC11TileIsNotCroppedByTheBydSafeAreaASecondTime() {
        val context = RuntimeEnvironment.getApplication()
        val upstream = ClusterTurnCardView(context)
        val c11 = ClusterTurnCardView(context, boundedC11Tile = true)
        assertEquals(150, upstream.cardRect(720, 720).width)
        assertEquals(58, upstream.cardRect(720, 720).height)
        assertEquals(396, c11.cardRect(720, 720).width)
        assertEquals(150, c11.cardRect(720, 720).height)
        c11.setLayout(20, 50, 90)
        assertEquals(648, c11.cardRect(720, 720).width)
        assertEquals(58, c11.cardRect(720, 720).left)
        assertEquals(108, c11.cardRect(720, 720).top)
        assertEquals(ClusterTurnCardOverlay.card(720, 720, 76, 30, 55), upstream.cardRect(720, 720))
    }

    @Test fun allC11PositionAndSizeChoicesStayInsideTheExistingNavigationTile() {
        for ((width, height) in listOf(720 to 720, 360 to 360)) {
            for (size in ClusterTurnCardOverlay.sizePercents) {
                for (x in ClusterTurnCardOverlay.xPercents) {
                    for (y in listOf(12, 30, 72)) {
                        val card = LeapmotorClusterTurnCardLayout.card(width, height, x, y, size)
                        assertTrue(card.left >= 0 && card.top >= 0)
                        assertTrue(card.left + card.width <= width)
                        assertTrue(card.top + card.height <= height)
                    }
                }
            }
        }
    }

    @Test fun compactFullAndArabicTurnCardsAtLargeFont() {
        render("compact-large", 360, Locale.US)
        render("full-large", 720, Locale.US)
        render("full-ar-rtl-large", 720, Locale.forLanguageTag("ar-SA"))
        render("full-explicit-size-position-large", 720, Locale.US, explicit = true)
    }

    private fun render(name: String, side: Int, locale: Locale, explicit: Boolean = false) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val original = Configuration(activity.resources.configuration)
        try {
            val config = Configuration(original).apply {
                fontScale = 1.5f
                setLocale(locale)
                setLayoutDirection(locale)
            }
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            val card = ClusterTurnCardView(activity, boundedC11Tile = true)
            if (explicit) card.setLayout(20, 72, 90)
            card.setGuidance(ClusterTurnGuidance(3, 0, 250,
                if (locale.language == "ar") "شارع الاستقلال" else "Avenida de la Independencia",
                arrivalEpochSeconds = 1_792_497_600L, remainingSeconds = 1_440, remainingMeters = 18_400))
            card.setBackgroundColor(Color.BLACK)
            card.measure(View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY))
            card.layout(0, 0, side, side)
            val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            try {
                card.draw(Canvas(bitmap))
                val rect = card.cardRect(side, side)
                val info = ReflectionHelpers.getField<RectF>(card, "rect")
                assertTrue("Route totals must remain inside the tile", info.left >= 0 && info.top >= 0 &&
                    info.right <= side && info.bottom <= side)
                assertEquals("Route totals belong at the bottom", side - LeapmotorClusterTurnCardLayout.verticalInset(side).toFloat(), info.bottom, 0.01f)
                assertEquals(LeapmotorClusterTurnCardLayout.verticalInset(side), rect.top)
                val rightEdge = side - LeapmotorClusterTurnCardLayout.rightInset(side)
                assertEquals(rightEdge, rect.left + rect.width)
                assertEquals(rightEdge.toFloat(), info.right, 0.01f)
                assertFalse("Route totals cannot overlap the maneuver banner", RectF.intersects(info,
                    RectF(rect.left.toFloat(), rect.top.toFloat(), (rect.left + rect.width).toFloat(),
                        (rect.top + rect.height).toFloat())))
                val distance = ReflectionHelpers.getField<Paint>(card, "distancePaint")
                val road = ReflectionHelpers.getField<Paint>(card, "roadPaint")
                assertEquals(rect.height * 0.30f * 1.5f, distance.textSize, 0.01f)
                assertEquals(rect.height * 0.17f * 1.5f, road.textSize, 0.01f)
                assertEquals(if (locale.language == "ar") Paint.Align.RIGHT else Paint.Align.LEFT, distance.textAlign)
                assertTrue(rect.left >= 0 && rect.top >= 0 && rect.left + rect.width <= side && rect.top + rect.height <= side)
                val directory = File("build/reports/ultra-c11-turn-card").apply { mkdirs() }
                File(directory, "$name.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        } finally {
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(original, activity.resources.displayMetrics)
            controller.pause().stop().destroy()
        }
    }
}
