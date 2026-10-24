package com.shilapi.xcertplay.ultra

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UltraVehiclePanelSessionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun reading(percent: Double = 61.0, range: Int = 257) =
        VehicleStatusSnapshot(range, null, percent, null, null, null, null)

    @Test fun cachedModelChangesRenderOnceAndMissingOrInvalidFactsWithdrawValues() {
        var snapshot: VehicleStatusSnapshot? = reading()
        var polls = 0
        val provider = VehicleStatusProvider { polls++; snapshot }
        UltraVehiclePanelSession(context, provider).use { session ->
            val first = checkNotNull(session.nextFrame())
            try {
                assertTrue(first.contentDescription.contains("61"))
                assertEquals(1, first.hitRegions.size)
                assertNull(session.nextFrame())
                snapshot = snapshot!!.copy(charging = true)
                assertNull("Charging is neither shown nor inferred", session.nextFrame())
                snapshot = null
                checkNotNull(session.nextFrame()).use { unavailable ->
                    assertTrue(unavailable.contentDescription.contains(context.getString(R.string.ultra_vehicle_data_unavailable)))
                    assertFalse(unavailable.contentDescription.contains("61"))
                    assertTrue(unavailable.hitRegions.isEmpty())
                }
                assertFalse("An old valid model's card cannot still be clicked", tap(session, first))
                for (bad in listOf(reading(Double.NaN), reading(-1.0), reading(101.0), reading(range = -1), reading(range = 65536))) {
                    snapshot = bad
                    assertNull("Every invalid model stays unavailable without needless redraw", session.nextFrame())
                }
                snapshot = reading(0.0, 0)
                checkNotNull(session.nextFrame()).use { zero ->
                    assertFalse(zero.contentDescription.contains(context.getString(R.string.ultra_vehicle_data_unavailable)))
                    assertEquals(1, zero.hitRegions.size)
                }
            } finally { first.close() }
            assertTrue(polls > 1)
        }
    }

    private fun tap(session: UltraVehiclePanelSession, frame: UltraVehiclePanelSession.Frame): Boolean {
        val hit = frame.hitRegions.firstOrNull() ?: return false
        return session.tap(frame, (hit.left + hit.right) / 2, (hit.top + hit.bottom) / 2)
    }

    @Test fun summaryDetailBackAndReactivationUseOnlyExactCurrentFrameGeometry() {
        UltraVehiclePanelSession(context, VehicleStatusProvider { reading() }).use { session ->
            val summary = checkNotNull(session.nextFrame(825, 360))
            try {
                assertFalse(session.tap(summary, Float.NaN, 0f))
                assertFalse(session.tap(summary, -1f, 0f))
                assertFalse(session.tap(summary, summary.bitmap.width.toFloat(), 0f))
                assertTrue(tap(session, summary))
                assertFalse("A repeated tap cannot act on the retired page", tap(session, summary))
                checkNotNull(session.nextFrame(825, 360)).use { details ->
                    assertEquals(UltraVehiclePanelSession.Page.BATTERY_DETAILS, details.page)
                    assertEquals(UltraVehiclePanelSession.Action.BACK, details.hitRegions.single().action)
                    assertTrue(tap(session, details))
                    assertFalse(tap(session, summary))
                }
                checkNotNull(session.nextFrame(825, 360)).use { again ->
                    assertTrue(again.sequence > summary.sequence)
                    assertEquals(UltraVehiclePanelSession.Page.SUMMARY, again.page)
                    session.invalidate()
                    assertFalse(tap(session, again))
                    checkNotNull(session.nextFrame(825, 360)).use { reactivated ->
                        assertTrue(reactivated.sequence > again.sequence)
                        assertTrue(tap(session, reactivated))
                    }
                }
            } finally { summary.close() }
        }
    }

    @Test fun callerOwnsDeliveredPixelsWhileClosedSessionRejectsCallbacksAndProviderPolls() {
        var polls = 0
        val session = UltraVehiclePanelSession(context, VehicleStatusProvider { polls++; reading() })
        val frame = checkNotNull(session.nextFrame())
        session.close()
        session.close()
        assertFalse(frame.bitmap.isRecycled)
        assertFalse(tap(session, frame))
        assertNull(session.nextFrame())
        assertEquals(1, polls)
        frame.close(); frame.close()
        assertTrue(frame.bitmap.isRecycled)
    }

    @Test fun resizeLocaleAndFontChangesInvalidateTheRenderedModelButNotBorrowedPixels() {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.US) }
        val scoped = context.createConfigurationContext(config)
        UltraVehiclePanelSession(scoped, VehicleStatusProvider { reading() }).use { session ->
            val first = checkNotNull(session.nextFrame())
            try {
                assertNull(session.nextFrame())
                checkNotNull(session.nextFrame(825, 360)).close()
                config.setLocale(Locale.forLanguageTag("ar-SA")); config.setLayoutDirection(Locale.forLanguageTag("ar-SA"))
                config.fontScale = 1.5f
                @Suppress("DEPRECATION")
                scoped.resources.updateConfiguration(config, scoped.resources.displayMetrics)
                checkNotNull(session.nextFrame(825, 360)).close()
                assertFalse(first.bitmap.isRecycled)
            } finally { first.close() }
        }
    }

    @Test fun rendererCompletionAfterCloseOrInvalidationRecyclesUnpublishedPixels() {
        for (close in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val image = AtomicReference<Bitmap>()
            val result = AtomicReference<UltraVehiclePanelSession.Frame?>()
            val error = AtomicReference<Throwable>()
            val renderer = object : UltraVehiclePanelRenderer(context) {
                override fun render(reading: UltraVehiclePanelSession.Reading?, page: UltraVehiclePanelSession.Page,
                    width: Int, height: Int, style: Style): Rendered {
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    image.set(bitmap)
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    return Rendered(bitmap, emptyList(), "fixture")
                }
            }
            val session = UltraVehiclePanelSession(VehicleStatusProvider { reading() }, renderer)
            val rendering = thread {
                try { result.set(session.nextFrame()) } catch (failure: Throwable) { error.set(failure) }
            }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                if (close) session.close() else session.invalidate()
            } finally { release.countDown(); rendering.join(3_000) }
            assertFalse(rendering.isAlive)
            assertNull(error.get())
            assertNull(result.get())
            assertTrue(image.get().isRecycled)
            session.close()
        }
    }

    @Test fun failingCacheProviderShowsUnavailableAndDimensionsAreBounded() {
        UltraVehiclePanelSession(context, VehicleStatusProvider { error("Synthetic cache failure") }).use { session ->
            checkNotNull(session.nextFrame()).use {
                assertTrue(it.contentDescription.contains(context.getString(R.string.ultra_vehicle_data_unavailable)))
                assertTrue(it.hitRegions.isEmpty())
            }
            assertThrows(IllegalArgumentException::class.java) { session.nextFrame(8192, 8192) }
            assertThrows(IllegalArgumentException::class.java) { session.nextFrame(1, 1) }
        }
    }
}
