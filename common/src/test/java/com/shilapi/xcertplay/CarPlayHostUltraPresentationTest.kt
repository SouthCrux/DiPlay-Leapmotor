// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.view.View
import com.shilapi.xcertplay.airplay.UltraReferenceCenterHost
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Canvas-submission readiness is distinct from stream/decode state; no phone or codec is started. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostUltraPresentationTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var panel: View
    private val sinks = mutableListOf<AndroidMediaSink>()

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        panel = View(activity)
        set("connectionPanel", panel)
        updateOverlays()
        assertEquals(View.VISIBLE, panel.visibility)
    }

    @After fun tearDown() {
        sinks.forEach(AndroidMediaSink::close)
        (get("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (get("teardownExecutor") as ExecutorService).shutdownNow()
        (get("airPlayCommandExecutor") as ExecutorService).shutdownNow()
    }

    @Test fun ordinarySinkNotifiesTheActivityAndDismissesThePreparingPanel() {
        val ordinary = createActivitySink(generation = 0)
        ordinary.onScreenStreamActive(110, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf(110), activeStreams())
        assertEquals(View.GONE, panel.visibility)

        ordinary.onScreenStreamActive(110, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activeStreams().isEmpty())
        assertEquals(View.VISIBLE, panel.visibility)
    }

    @Test fun ultraSubmissionEventDismissesThePreparingPanelAndSuspensionRestoresIt() {
        val host = mock(UltraReferenceCenterHost::class.java)
        `when`(host.submittedFrames).thenReturn(3L)
        val attachment = mock(UltraReferenceCenterHost.Attachment::class.java)
        set("referenceCenterHost", host)
        set("referenceCenterAttachment", attachment)
        // UltraVideoPipeline creates its own AndroidMediaSink with no Activity callback. Creating
        // the same sink shape isolates the missing notification without needing GL or codec input.
        val ultra = AndroidMediaSink(surface = null, videoWidth = 1920, videoHeight = 720).also { sinks += it }
        ultra.onScreenStreamActive(110, true)
        shadowOf(Looper.getMainLooper()).idle()
        activity.javaClass.getDeclaredMethod("setConnectionStage", String::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(activity, "Preparing CarPlay", "Preparing CarPlay")

        assertEquals(3L, host.submittedFrames)
        assertTrue("The Ultra sink never notified the Activity's stream-state set", activeStreams().isEmpty())
        outputChanged(host, attachment)
        assertEquals(View.VISIBLE, panel.visibility)
        `when`(attachment.isOutputReady).thenReturn(true)
        outputChanged(host, attachment)
        assertEquals(View.GONE, panel.visibility)
        assertTrue(activeStreams().isEmpty())
        assertEquals(true, get("referenceOutputReady"))
        `when`(attachment.isOutputReady).thenReturn(false)
        outputChanged(host, attachment)
        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(false, get("referenceOutputReady"))
        assertEquals("Preparing CarPlay", get("latestStage"))
    }

    @Test fun staleHostOrAttachmentEventsCannotHideTheCurrentPreparingPanel() {
        val host = mock(UltraReferenceCenterHost::class.java)
        val current = mock(UltraReferenceCenterHost.Attachment::class.java)
        val stale = mock(UltraReferenceCenterHost.Attachment::class.java)
        `when`(current.isOutputReady).thenReturn(true)
        `when`(stale.isOutputReady).thenReturn(true)
        set("referenceCenterHost", host); set("referenceCenterAttachment", current)

        outputChanged(mock(UltraReferenceCenterHost::class.java), current)
        outputChanged(host, stale)

        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(false, get("referenceOutputReady"))
        outputChanged(host, current)
        assertEquals(View.GONE, panel.visibility)
    }

    @Test fun anActiveStreamCannotReplaceCanvasReadinessAndUnbindingClearsThatReadiness() {
        val host = mock(UltraReferenceCenterHost::class.java)
        val attachment = mock(UltraReferenceCenterHost.Attachment::class.java)
        val controller = mock(CarPlayController::class.java)
        `when`(controller.referenceCenterHost).thenReturn(host)
        set("controller", controller)
        set("referenceCenterHost", host); set("referenceCenterAttachment", attachment)
        val ordinary = createActivitySink(generation = 0)
        ordinary.onScreenStreamActive(110, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf(110), activeStreams())
        assertEquals(View.VISIBLE, panel.visibility)
        `when`(attachment.isOutputReady).thenReturn(true)
        outputChanged(host, attachment)
        assertEquals(View.GONE, panel.visibility)
        activity.javaClass.getDeclaredMethod("unbindReferenceHost").apply { isAccessible = true }.invoke(activity)
        assertEquals(false, get("referenceOutputReady"))
        // Ordinary stream ownership remains independent; teardown of that stream restores its panel.
        ordinary.onScreenStreamActive(110, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, panel.visibility)
        verify(attachment).close()
        verify(host, never()).close()
    }

    @Test fun aLateOrdinarySinkFromAnOlderGenerationCannotDismissTheNewPreparingPanel() {
        set("restartGeneration", 2)
        createActivitySink(generation = 1).onScreenStreamActive(110, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activeStreams().isEmpty())
        assertEquals(View.VISIBLE, panel.visibility)
    }

    private fun createActivitySink(generation: Int): AndroidMediaSink =
        (activity.javaClass.getDeclaredMethod("createMediaSink", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(activity, 1920, 720, generation) as AndroidMediaSink).also { sinks += it }
    private fun updateOverlays() = activity.javaClass.getDeclaredMethod("updateDebugOverlays")
        .apply { isAccessible = true }.invoke(activity)
    private fun outputChanged(host: UltraReferenceCenterHost, attachment: UltraReferenceCenterHost.Attachment) {
        activity.javaClass.getDeclaredMethod("onReferenceOutputChanged", UltraReferenceCenterHost::class.java,
            UltraReferenceCenterHost.Attachment::class.java).apply { isAccessible = true }.invoke(activity, host, attachment)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Suppress("UNCHECKED_CAST")
    private fun activeStreams() = get("activeScreenStreamTypes") as Set<Int>
    private fun get(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun set(name: String, value: Any?) = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(activity, value)
}
