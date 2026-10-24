package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.UltraLocalSettingsInput
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
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
class UltraVehiclePanelInputTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun reading() = VehicleStatusSnapshot(257, null, 61.0, null, null, null, null)

    @Test fun actualPanelPixelsCoalesceAndExactTokenTapsProduceIndependentNewFrames() {
        var hits = emptyList<UltraVehiclePanelSession.HitRegion>()
        val renderer = object : UltraVehiclePanelRenderer(context) {
            override fun render(reading: UltraVehiclePanelSession.Reading?, page: UltraVehiclePanelSession.Page,
                width: Int, height: Int, style: Style): Rendered =
                super.render(reading, page, width, height, style).also { hits = it.hits }
        }
        UltraVehiclePanelInput(UltraVehiclePanelSession(VehicleStatusProvider { reading() }, renderer)).use { input ->
            checkNotNull(input.nextFrame(12_000_000_000)).use { summary ->
                assertEquals(UltraLocalSettingsInput.WIDTH, summary.bitmap.width)
                assertEquals(UltraLocalSettingsInput.HEIGHT, summary.bitmap.height)
                assertEquals(12_000_000_000, summary.senderNanos)
                assertNull(input.nextFrame(13_000_000_000))
                val card = hits.single()
                val x = (card.left + card.right) / 2
                val y = (card.top + card.bottom) / 2
                assertFalse(input.tap(summary.token + 1, x, y))
                assertTrue(input.tap(summary.token, x, y))
                assertFalse(input.tap(summary.token, x, y))
                checkNotNull(input.nextFrame(14_000_000_000)).use { detail ->
                    assertTrue(detail.token > summary.token)
                    assertEquals(UltraVehiclePanelSession.Action.BACK, hits.single().action)
                    assertFalse(summary.bitmap.isRecycled)
                    input.invalidate()
                    assertFalse(input.tap(detail.token, 0f, 0f))
                    checkNotNull(input.nextFrame(11_000_000_000)).use { reactivated ->
                        assertTrue(reactivated.token > detail.token)
                        assertEquals(14_000_000_000, reactivated.senderNanos)
                    }
                }
            }
        }
    }

    @Test fun missingReadingsReplaceTheImageAndClosingTheSourceNeverRecyclesTransferredLeases() {
        var snapshot: VehicleStatusSnapshot? = reading()
        var polls = 0
        val input = UltraVehiclePanelInput(context, VehicleStatusProvider { polls++; snapshot })
        val valid = checkNotNull(input.nextFrame(1))
        snapshot = null
        val unavailable = checkNotNull(input.nextFrame(2))
        assertTrue(unavailable.token > valid.token)
        assertFalse(input.tap(valid.token, 800f, 400f))
        assertFalse(input.tap(unavailable.token, 800f, 400f))
        input.close(); input.close()
        assertNull(input.nextFrame(3))
        assertEquals(2, polls)
        assertFalse(valid.bitmap.isRecycled)
        assertFalse(unavailable.bitmap.isRecycled)
        valid.close(); valid.close(); unavailable.close()
        assertTrue(valid.bitmap.isRecycled)
        assertTrue(unavailable.bitmap.isRecycled)
    }
}
