package com.shilapi.xcertplay.airplay

import android.graphics.Bitmap
import com.shilapi.xcertplay.media.RetainedVideoFrameWorker
import com.shilapi.xcertplay.media.VideoCompositionFrame
import com.shilapi.xcertplay.media.VideoSurfaceOrigins
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UltraOutputRecoveryTest {
    @Test fun aStaticFrameRetriesAfterResumeEvenWithoutAnOutgoingProtocolMessage() {
        val recovery = UltraOutputRecovery()
        val sceneReady = AtomicBoolean(true)
        val attempts = LinkedBlockingQueue<String>()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val pts = 12_000_000_000L
        val worker = RetainedVideoFrameWorker({ frame ->
            try {
                assertSame(bitmap, frame.bitmap)
                assertEquals("A redraw must not manufacture a new phone timestamp", pts, frame.senderNanos)
                if (sceneReady.get()) {
                    recovery.submitted()
                    attempts.add("accepted")
                } else attempts.add("not-ready")
            } finally { frame.close() }
        }, { attempts.add("error") })
        try {
            recovery.visibility(true, true)
            worker.offer(VideoCompositionFrame(VideoSurfaceOrigins.newToken(), pts, bitmap))
            assertEquals("accepted", attempts.poll(5, TimeUnit.SECONDS))
            assertFalse(recovery.needsRefresh(false))
            recovery.visibility(false, true)
            recovery.invalidated()
            assertFalse("Camera foreground must not trigger output recovery", recovery.needsRefresh(false))
            sceneReady.set(false)
            recovery.visibility(true, true)
            worker.requestRefresh()
            assertEquals("not-ready", attempts.poll(5, TimeUnit.SECONDS))
            sceneReady.set(true)
            assertTrue("A silent protocol poll must retry an unsubmitted activation", recovery.needsRefresh(false))
            if (recovery.needsRefresh(false)) worker.requestRefresh()
            assertEquals("accepted", attempts.poll(5, TimeUnit.SECONDS))
            assertFalse("Recovery stops after accepted Canvas publication", recovery.needsRefresh(false))
            recovery.invalidated()
            assertTrue("Replacing a surface requires publication again", recovery.needsRefresh(false))
            recovery.visibility(true, false)
            assertFalse("Missing surface must not start a redraw loop", recovery.needsRefresh(false))
        } finally {
            worker.close()
            assertTrue(worker.awaitClosed(5_000))
        }
        assertTrue(bitmap.isRecycled)
    }
}
