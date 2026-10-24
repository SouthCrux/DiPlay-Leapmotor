package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterTurnOverlayExpiryTest {
    @Test fun silentRouteClearsThePublishedCardWithoutAnotherPhonePacket() = withRoute { route, advance, events ->
        advance(120_000_000_000L)
        assertNull(route.currentApple())
        CarPlayTurnOverlay.refresh()
        assertNull(events.last())
        assertEquals(2, events.size)
        CarPlayTurnOverlay.refresh()
        assertEquals(2, events.size)
    }

    @Test fun emptyManeuverListClearsTheCardAfterItsGracePeriod() = withRoute { route, advance, events ->
        route.accept(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlv(0x0d))
        advance(8_000_000_000L)
        CarPlayTurnOverlay.refresh()
        assertNull(events.last())
        route.accept(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlv(0x0d, 0, 7))
        CarPlayTurnOverlay.refresh()
        assertEquals(3, events.size)
        assertEquals(150, events.last()!!.distanceMeters)
    }

    @Test fun endingTheSessionKeepsTheTurnCardAcrossTheDrop() = withRoute { route, _, events ->
        CarPlayTurnOverlay.endNow(preserveAcrossDrop = true)
        assertEquals(150, route.currentApple()!!.distanceMeters)
        assertEquals(150, events.last()!!.distanceMeters)
    }

    @Test fun explicitStopStillClearsTheTurnCardImmediately() = withRoute { route, _, events ->
        CarPlayTurnOverlay.endNow()
        assertNull(route.currentApple())
        assertNull(events.last())
        assertEquals(2, events.size)
    }

    @Test fun noRoutePacketsDoNotExtendRetainedGuidanceOrReplaceItsRoad() = withRoute { route, advance, events ->
        val before = route.currentApple()
        advance(119_000_000_000L)
        route.accept(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlv(0x01, 0) + tlv(0x03, 88))
        assertEquals(before, route.currentApple())
        advance(120_000_000_000L)
        route.accept(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlv(0x01, 0))
        CarPlayTurnOverlay.refresh()
        assertNull(route.currentApple())
        assertNull(events.last())
        route.accept(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlv(0x01, 2))
        route.accept(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlv(0x0a, 0, 0, 0, 5))
        assertNull(route.currentApple())
    }

    private fun withRoute(test: (BydHudRouteState, (Long) -> Unit, MutableList<ClusterTurnGuidance?>) -> Unit) {
        var now = 0L
        val field = CarPlayTurnOverlay::class.java.getDeclaredField("route").apply { isAccessible = true }
        val route = field.get(CarPlayTurnOverlay) as BydHudRouteState
        val clock = BydHudRouteState::class.java.getDeclaredField("nanoTime").apply { isAccessible = true }
        val previousClock = clock.get(route)
        route.clear()
        clock.set(route, { now })
        route.accept(BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlv(0x01, 0, 7) + tlv(0x03, 1))
        route.accept(BydHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlv(0x01, 1) + tlv(0x0a, 0, 0, 0, 150) + tlv(0x0d, 0, 7))
        val events = mutableListOf<ClusterTurnGuidance?>()
        try {
            CarPlayTurnOverlay.setListener { events += it }
            test(route, { now = it }, events)
        } finally {
            CarPlayTurnOverlay.setListener(null)
            route.clear()
            clock.set(route, previousClock)
        }
    }

    private fun tlv(type: Int, vararg bytes: Int): ByteArray =
        byteArrayOf(0, (bytes.size + 4).toByte(), (type ushr 8).toByte(), type.toByte()) +
            bytes.map(Int::toByte).toByteArray()
}
