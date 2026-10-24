// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.ultra

import android.graphics.Bitmap
import android.graphics.Color
import com.shilapi.xcertplay.media.VideoCompositionFrame
import com.shilapi.xcertplay.airplay.UltraLocalSettingsFrame
import com.shilapi.xcertplay.airplay.UltraLocalSettingsInput
import com.shilapi.xcertplay.airplay.UltraSceneUnavailableException
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import org.robolectric.RuntimeEnvironment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UltraReferenceCenterDrawTest {
    @Test fun actualVehicleSourceOpensBatteryDetailsAndReturnsOnlyAfterItsCanvasWasSubmitted() {
        var hits = emptyList<UltraVehiclePanelSession.HitRegion>()
        val context = RuntimeEnvironment.getApplication()
        val renderer = object : UltraVehiclePanelRenderer(context) {
            override fun render(reading: UltraVehiclePanelSession.Reading?,page: UltraVehiclePanelSession.Page,
                width: Int,height: Int,style: Style): Rendered = super.render(reading,page,width,height,style).also { hits=it.hits }
        }
        val provider = VehicleStatusProvider { VehicleStatusSnapshot(257,null,61.0,null,null,null,null) }
        UltraVehiclePanelInput(UltraVehiclePanelSession(provider,renderer)).use { input ->
            UltraReferenceCenterDraw.Session({ error("No PNG expected") },input).use { drawer ->
                frame(42,12_000_000_000,Color.RED).use { phone ->
                    fun contacts(down: Boolean): List<AirPlayContact> {
                        val h=hits.single()
                        return listOf(AirPlayContact(0,((h.left+h.right)/2/1650).toDouble(),
                            ((h.top+h.bottom)/2/720).toDouble(),down))
                    }
                    checkNotNull(input.nextFrame(12_000_000_000)).use { summary ->
                        val bytes=report(summary.token,12.0,UltraLocalSettingsInput.IDENTIFIER).toString().toByteArray()
                        val canvas=drawer.drawWithSettings(bytes,phone,null,summary,null)
                        try {
                            assertFalse(drawer.handleTouch(contacts(true)))
                            assertFalse(drawer.handleTouch(contacts(false)))
                            drawer.outputSubmitted(canvas)
                            assertTrue(drawer.handleTouch(contacts(true)))
                            assertTrue(drawer.handleTouch(contacts(false)))
                            assertFalse(canvas.isRecycled)
                        } finally { canvas.recycle() }
                        checkNotNull(input.nextFrame(13_000_000_000)).use { detail ->
                            assertEquals(UltraVehiclePanelSession.Action.BACK,hits.single().action)
                            val detailBytes=report(detail.token,13.0,UltraLocalSettingsInput.IDENTIFIER).toString().toByteArray()
                            val next=drawer.drawWithSettings(detailBytes,phone,null,detail,null)
                            try {
                                drawer.outputSubmitted(next)
                                assertTrue(drawer.handleTouch(contacts(true)))
                                assertTrue(drawer.handleTouch(contacts(false)))
                                checkNotNull(input.nextFrame(14_000_000_000)).use {
                                    assertEquals(UltraVehiclePanelSession.Action.BATTERY_DETAILS,hits.single().action)
                                }
                                drawer.outputInvalidated()
                                assertFalse(drawer.handleTouch(contacts(true)))
                                assertFalse(drawer.handleTouch(contacts(false)))
                            } finally { next.recycle() }
                        }
                    }
                }
            }
        }
    }
    private class SettingsFrame(override val token: Long = 42,
        override val senderNanos: Long = 12_125_000_000,
        width: Int = UltraLocalSettingsInput.WIDTH,
    ) : UltraLocalSettingsFrame {
        override val bitmap = Bitmap.createBitmap(width, UltraLocalSettingsInput.HEIGHT, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.GREEN) }
        override fun close() { if (!bitmap.isRecycled) bitmap.recycle() }
    }
    // The decoder owns frame construction in :shared. Reflection creates only synthetic pixels for
    // this cross-module test and leaves the production frame constructor restricted to its owner.
    private fun frame(token: Long, nanos: Long, color: Int): VideoCompositionFrame {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val onClosed: () -> Unit = {}
        return VideoCompositionFrame::class.java.constructors.single { it.parameterCount == 4 }
            .newInstance(token, nanos, bitmap, onClosed) as VideoCompositionFrame
    }

    private fun report(token: Long = 42, pts: Double = 12.125, identifier: String = "iPhoneVideo") = JSONObject().apply {
        put("fixture", true); put("fullUltraUI", false); put("incompleteBridgeOverride", true)
        put("scene", JSONObject().apply {
            fun point(x: Int, y: Int) = JSONObject().put("x", x).put("y", y)
            fun rgba(color: Int) = JSONObject().put("r", Color.red(color) / 255.0)
                .put("g", Color.green(color) / 255.0).put("b", Color.blue(color) / 255.0)
                .put("a", Color.alpha(color) / 255.0)
            put("bounds", JSONObject().put("origin", point(0, 0))
                .put("size", JSONObject().put("width", 8).put("height", 6)))
            put("position", point(0, 0)); put("anchorPoint", point(0, 0))
            put("transform", JSONObject().put("a", 1).put("b", 0).put("c", 0).put("d", 1).put("tx", 0).put("ty", 0))
            put("backgroundColor", rgba(Color.BLACK)); put("opacity", 1)
            put("hidden", false); put("maskToBounds", false); put("children", JSONArray())
            put("video", JSONObject().put("identifier", identifier).put("active", true)
                .put("token", token).put("pts", pts))
        })
    }

    private fun draw(report: JSONObject, offered: VideoCompositionFrame, current: VideoCompositionFrame? = null) =
        UltraReferenceCenterDraw.draw(report.toString().toByteArray(Charsets.UTF_8), offered, current) {
            error("No PNG expected")
        }

    @Test fun selectsOfferedAndRetainedFramesByExactTokenAndOriginWithoutOwningTheirPixels() {
        frame(42, 12_125_000_000, Color.RED).use { offered ->
            frame(41, 11_000_000_000, Color.BLUE).use { current ->
                for ((selected, color) in listOf(report() to Color.RED, report(41, 11.0) to Color.BLUE)) {
                    val output = draw(selected, offered, current)
                    try {
                        assertEquals(8, output.width); assertEquals(6, output.height)
                        assertEquals(color, output.getPixel(4, 3))
                        assertNotSame(offered.bitmap, output); assertNotSame(current.bitmap, output)
                    } finally { output.recycle() }
                    assertFalse(offered.bitmap.isRecycled); assertFalse(current.bitmap.isRecycled)
                }
            }
        }
    }

    @Test fun wrongInputStaleTokenOrMismatchedPtsCannotBorrowAFrame() {
        frame(42, 12_125_000_000, Color.RED).use { offered ->
            frame(41, 11_000_000_000, Color.BLUE).use { current ->
                for (invalid in listOf(report(identifier = "ClusterVideo"), report(token = 40),
                    report(pts = 12.125002), report(41, 12.125))) {
                    assertThrows(IllegalStateException::class.java) { draw(invalid, offered, current) }
                    assertEquals(Color.RED, offered.bitmap.getPixel(0, 0))
                    assertEquals(Color.BLUE, current.bitmap.getPixel(0, 0))
                }
                assertThrows(IllegalStateException::class.java) { draw(report(41, 11.0), offered) }
            }
        }
    }

    @Test fun requiresTypedResearchFlagsAndRejectsFullUiClaims() {
        frame(42, 12_125_000_000, Color.RED).use { offered ->
            for ((key, value) in listOf("fixture" to false, "fullUltraUI" to true,
                "incompleteBridgeOverride" to false, "fixture" to "true")) {
                assertThrows(IllegalStateException::class.java) { draw(report().put(key, value), offered) }
            }
            assertFalse(offered.bitmap.isRecycled)
        }
    }

    @Test fun anUnbuiltOriginalRootIsExplicitlyUnavailableAndNeverAComposedBlackFrame() {
        frame(42, 12_125_000_000, Color.RED).use { offered ->
            val unavailable = report().put("scene", JSONObject().put("rootUnavailable", true))
            assertThrows(UltraSceneUnavailableException::class.java) { draw(unavailable, offered) }
            assertFalse(offered.bitmap.isRecycled)
            assertEquals(Color.RED, offered.bitmap.getPixel(0, 0))
        }
    }

    @Test fun settingsUsesItsOwnFrameNamespaceAndBorrowsPixelsWithoutRetainingThem() {
        frame(42, 12_125_000_000, Color.RED).use { phone ->
            SettingsFrame().use { settings ->
                val bytes = report(identifier = UltraLocalSettingsInput.IDENTIFIER).toString().toByteArray()
                val drawer = UltraReferenceCenterDraw.Session({ error("No PNG expected") })
                for ((offered, current) in listOf(settings to null, null to settings)) {
                    val output = drawer.drawWithSettings(bytes, phone, null, offered, current)
                    try { assertEquals(Color.GREEN, output.getPixel(4, 3)) }
                    finally { output.recycle() }
                    assertFalse(settings.bitmap.isRecycled)
                    assertEquals(Color.RED, phone.bitmap.getPixel(0, 0))
                }
                drawer.close()
                assertFalse(settings.bitmap.isRecycled)
            }
        }
    }

    @Test fun settingsRejectsMissingStaleMismatchedOrInvalidLocalFramesInsteadOfUsingPhonePixels() {
        frame(42, 12_125_000_000, Color.RED).use { phone ->
            UltraReferenceCenterDraw.Session({ error("No PNG expected") }).use { drawer ->
                fun draw(settings: SettingsFrame?, token: Long = 42, pts: Double = 12.125) = drawer.drawWithSettings(
                    report(token, pts, UltraLocalSettingsInput.IDENTIFIER).toString().toByteArray(),
                    phone, null, settings, null)
                assertThrows(IllegalStateException::class.java) { draw(null) }
                SettingsFrame().use { settings ->
                    assertThrows(IllegalStateException::class.java) { draw(settings, token = 41) }
                    assertThrows(IllegalStateException::class.java) { draw(settings, pts = 12.126) }
                    settings.close()
                    assertThrows(IllegalStateException::class.java) { draw(settings) }
                }
                SettingsFrame(width = 2).use { settings ->
                    assertThrows(IllegalStateException::class.java) { draw(settings) }
                }
                assertFalse(phone.bitmap.isRecycled)
            }
        }
    }
}
