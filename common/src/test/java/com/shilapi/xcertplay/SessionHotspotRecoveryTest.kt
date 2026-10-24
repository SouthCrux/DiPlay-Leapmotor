package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.network.CarHotspotTethering
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class SessionHotspotRecoveryTest {
    private fun advance() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

    @Test fun recoversWithoutAnyVisibleActivityAndStopsOnServiceDestruction() {
        val controller = Robolectric.buildService(DiPlaySessionService::class.java).create()
        val service = controller.get()
        var starts = 0
        service.hotspotRecoveryFactory = {
            ForegroundHotspotRecovery({ true }, { false }, { starts++; CarHotspotTethering.Result.READY }, { it() })
        }
        try {
            service.onStartCommand(Intent(), 0, 1)
            service.onStartCommand(Intent(), 0, 2)
            advance()
            assertEquals("Repeated start commands must not duplicate the recovery timer", 1, starts)
        } finally { controller.destroy() }
        advance()
        assertEquals("No startup after the foreground connection service ends", 1, starts)
    }

    @Test fun explicitDisconnectCancelsRecoveryBeforeServiceDestruction() {
        val controller = Robolectric.buildService(DiPlaySessionService::class.java).create()
        val service = controller.get()
        var starts = 0
        service.hotspotRecoveryFactory = {
            ForegroundHotspotRecovery({ true }, { false }, { starts++; CarHotspotTethering.Result.READY }, { it() })
        }
        try {
            service.onStartCommand(Intent(), 0, 1)
            service.onStartCommand(Intent().setAction(DiPlaySessionService.ACTION_STOP), 0, 2)
            advance()
            assertEquals(0, starts)
        } finally { controller.destroy() }
    }
}
