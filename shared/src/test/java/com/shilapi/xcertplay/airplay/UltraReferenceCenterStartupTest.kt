package com.shilapi.xcertplay.airplay

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.transport.Iap2ThemeAsset
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class UltraReferenceCenterStartupTest {
    private class Timer {
        data class Task(val delay: Long, val callback: () -> Unit, var canceled: Boolean = false)
        var now = 0L
        val tasks = mutableListOf<Task>()
        fun schedule(delay: Long, action: () -> Unit): Closeable {
            val task = Task(delay, action)
            tasks += task
            return Closeable { task.canceled = true }
        }
    }

    private class Output : UltraReferenceCenterHost.Output {
        var attached: Surface? = null
        var submissions = 0
        var acceptsPublication = true
        var beforePublish: (() -> Unit)? = null
        override fun attach(surface: Surface) { attached = surface }
        override fun detach(surface: Surface) { if (attached === surface) attached = null }
        override fun publish(bitmap: Bitmap): Boolean {
            check(attached != null)
            if (!acceptsPublication) return false
            beforePublish?.invoke()
            submissions++
            return true
        }
        override fun close() { attached = null }
    }

    private class Connection(val emit: (Bitmap) -> Unit) : UltraReferenceConnection {
        var closes = 0
        val visibility = mutableListOf<Pair<Boolean, Boolean>>()
        override val videoSink = object : MediaSink {}
        override fun panel(id: String): UiSyncPanelSession {
            require(id == "Center_Display")
            return object : UiSyncPanelSession {
                override fun poll() = emptyList<ByteArray>()
                override fun receive(bytes: ByteArray) = emptyList<ByteArray>()
                override fun close() = Unit
            }
        }
        override fun updateHostVisibility(resumed: Boolean, surfaceReady: Boolean) { visibility += resumed to surfaceReady }
        override fun requestAccessoryScene(request: Boolean) { check(request) }
        override fun close() { closes++ }
    }

    private class Fixture : Closeable {
        val timer = Timer()
        val output = Output()
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        lateinit var connection: Connection
        var failures = 0
        var connectionFailures = 0
        val dispatchedTimeouts = mutableListOf<() -> Unit>()
        var beforeTimeoutDelivery: (() -> Unit)? = null
        val diagnostics = mutableListOf<String>()
        var diagnosticThrows = false
        val host = UltraReferenceCenterHost(
            UltraThemeBinding(Iap2ThemeAsset("44C9FA31EC", 985, "19.0"), UltraThemeDisplay("Center_Display", 1920, 720, 157)),
            { publish, _ -> Connection(publish).also { connection = it } }, {}, output,
            startupFactory = { timeout ->
                dispatchedTimeouts += timeout
                UltraReferenceStartupWatchdog(clock = { timer.now }, schedule = timer::schedule,
                    onTimeout = { beforeTimeoutDelivery?.invoke(); timeout() })
            },
            onDiagnostic = { diagnostics += it; if (diagnosticThrows) error("fixture logger failure") },
        )
        val attachment = host.attachHost { failures++ }
        fun visible() {
            attachment.updateResumed(true)
            attachment.updateSurface(surface, 1920, 720)
        }
        fun renderer() = host.renderer(onConnectionFailure = { connectionFailures++ })
        override fun close() {
            host.close()
            bitmap.recycle(); surface.release(); texture.release()
        }
    }

    @Test fun missingChannelTimesOutOnceAndRetiresOwnerWithoutAcceptingLateFrames() {
        Fixture().use { f ->
            f.visible()
            val renderer = f.renderer()
            assertEquals(10_000L, f.timer.tasks.single().delay)
            f.connection.emit(f.bitmap)
            assertFalse(f.attachment.isOutputReady)
            f.timer.now = 10_000
            f.timer.tasks.single().callback()
            assertEquals(UltraReferenceFailure.UI_SYNC_CHANNEL_TIMEOUT, f.host.failureReason)
            assertEquals(1, f.failures)
            assertEquals(1, f.connectionFailures)
            assertEquals(1, f.connection.closes)
            assertEquals(false to false, f.connection.visibility.last())
            assertFalse(f.attachment.isOutputReady)
            assertNull(f.output.attached)
            f.connection.emit(f.bitmap)
            f.timer.tasks.single().callback()
            assertEquals(0, f.output.submissions)
            assertEquals(0L, f.host.submittedFrames)
            assertEquals(1, f.failures)
            assertThrows(IllegalStateException::class.java) { renderer.panel("Center_Display") }
        }
    }

    @Test fun openingThePanelCancelsStartupTimeoutAndAllowsOutputReadiness() {
        Fixture().use { f ->
            f.visible()
            val renderer = f.renderer()
            val task = f.timer.tasks.single()
            renderer.panel("Center_Display")
            assertTrue(task.canceled)
            val frameDeadline = f.timer.tasks.last()
            assertNotSame(task, frameDeadline)
            assertEquals(10_000L, frameDeadline.delay)
            f.connection.emit(f.bitmap)
            assertTrue(frameDeadline.canceled)
            assertTrue(f.attachment.isOutputReady)
            assertEquals(1, f.output.submissions)
            f.timer.now = 100_000
            task.callback()
            frameDeadline.callback()
            assertNull(f.host.failureReason)
            assertEquals(0, f.failures)
            assertEquals(0, f.connection.closes)
            renderer.close()
            assertFalse(f.attachment.isOutputReady)
        }
    }

    @Test fun backgroundAndMissingSurfacesPreserveOnlyTheRemainingVisibleStartupBudget() {
        Fixture().use { f ->
            f.renderer()
            assertTrue(f.timer.tasks.isEmpty())
            f.visible()
            val initial = f.timer.tasks.last()
            f.timer.now = 2_000
            f.attachment.updateResumed(false)
            assertTrue(initial.canceled)
            f.timer.now = 500_000
            initial.callback()
            assertEquals(0, f.failures)
            f.attachment.updateResumed(true)
            assertEquals(8_000L, f.timer.tasks.last().delay)
            f.timer.now += 3_000
            f.attachment.detach(f.surface)
            val paused = f.timer.tasks.last()
            assertTrue(paused.canceled)
            f.timer.now += 500_000
            paused.callback()
            f.attachment.updateSurface(f.surface, 1920, 720)
            assertEquals(5_000L, f.timer.tasks.last().delay)
            f.timer.now += 5_000
            f.timer.tasks.last().callback()
            assertEquals(UltraReferenceFailure.UI_SYNC_CHANNEL_TIMEOUT, f.host.failureReason)
            assertEquals(1, f.failures)
        }
    }
    @Test fun anOpenedChannelWithoutAcceptedCanvasTimesOutOnceWithoutResettingOnDuplicatePanel() {
        Fixture().use { f ->
            f.visible()
            val renderer = f.renderer()
            val channel = f.timer.tasks.single()
            f.timer.now = 7_000
            renderer.panel("Center_Display")
            val frame = f.timer.tasks.last()
            assertEquals(10_000L, frame.delay)
            renderer.panel("Center_Display")
            assertEquals("Duplicate panel opens cannot replenish the first-frame budget", 2, f.timer.tasks.size)
            f.output.acceptsPublication = false
            f.connection.emit(f.bitmap)
            assertFalse(f.attachment.isOutputReady)
            f.timer.now = 10_000
            channel.callback()
            assertNull(f.host.failureReason)
            f.timer.now = 17_000
            frame.callback()
            frame.callback()
            channel.callback()
            assertEquals(UltraReferenceFailure.FIRST_FRAME_TIMEOUT, f.host.failureReason)
            assertEquals(1, f.failures)
            assertEquals(1, f.connectionFailures)
            assertEquals(1, f.connection.closes)
            assertNull(f.output.attached)
            assertEquals(0L, f.host.submittedFrames)
        }
    }

    @Test fun firstFrameDeadlineSuspendsAcrossPauseAndUnusableSurfaceWithoutCountingBackgroundTime() {
        Fixture().use { f ->
            f.visible()
            f.renderer().panel("Center_Display")
            val frame = f.timer.tasks.last()
            f.timer.now = 2_000
            f.attachment.updateResumed(false)
            assertTrue(frame.canceled)
            f.timer.now = 500_000
            frame.callback()
            assertNull(f.host.failureReason)
            f.attachment.updateResumed(true)
            assertEquals(8_000L, f.timer.tasks.last().delay)
            f.timer.now += 3_000
            f.attachment.updateSurface(f.surface, 1920, 719)
            val unavailable = f.timer.tasks.last()
            assertTrue(unavailable.canceled)
            f.timer.now += 500_000
            unavailable.callback()
            assertNull(f.host.failureReason)
            f.attachment.updateSurface(f.surface, 1920, 720)
            assertEquals(5_000L, f.timer.tasks.last().delay)
            f.timer.now += 5_000
            f.timer.tasks.last().callback()
            assertEquals(UltraReferenceFailure.FIRST_FRAME_TIMEOUT, f.host.failureReason)
            assertEquals(1, f.failures)
        }
    }

    @Test fun canvasFromRetiredActivationCannotCancelFirstFrameDeadline() {
        Fixture().use { f ->
            f.visible()
            f.renderer().panel("Center_Display")
            val frame = f.timer.tasks.last()
            // The Canvas completes, but its activation was replaced during submission.
            f.output.beforePublish = {
                f.output.beforePublish = null
                f.attachment.updateResumed(false)
                f.attachment.updateResumed(true)
            }
            f.connection.emit(f.bitmap)
            assertEquals(1, f.output.submissions)
            assertFalse(f.attachment.isOutputReady)
            assertEquals(0L, f.host.submittedFrames)
            assertTrue(frame.canceled)
            val resumed = f.timer.tasks.last()
            assertFalse(resumed.canceled)
            f.timer.now = 10_000
            frame.callback()
            assertNull(f.host.failureReason)
            resumed.callback()
            assertEquals(UltraReferenceFailure.FIRST_FRAME_TIMEOUT, f.host.failureReason)
        }
    }

    @Test fun connectionReplacementAndCloseRetirePendingFrameDeadlines() {
        Fixture().use { f ->
            f.visible()
            val oldRenderer = f.renderer()
            oldRenderer.panel("Center_Display")
            val oldFrame = f.timer.tasks.last()
            val oldConnection = f.connection
            val replacement = f.renderer()
            assertTrue(oldFrame.canceled)
            replacement.panel("Center_Display")
            val currentFrame = f.timer.tasks.last()
            oldConnection.emit(f.bitmap)
            f.timer.now = 10_000
            oldFrame.callback()
            assertNull(f.host.failureReason)
            assertFalse(f.attachment.isOutputReady)
            assertFalse(currentFrame.canceled)
            replacement.close()
            assertTrue(currentFrame.canceled)
            currentFrame.callback()
            assertNull(f.host.failureReason)
            assertEquals(0, f.failures)
            assertEquals(0, f.connectionFailures)
        }
    }

    @Test fun acceptedCanvasCompletesItsActivationAndRepeatedResumeDoesNotReplenishTheNextDeadline() {
        Fixture().use { f ->
            f.visible()
            f.renderer().panel("Center_Display")
            val frame = f.timer.tasks.last()
            f.connection.emit(f.bitmap)
            assertTrue(frame.canceled)
            assertTrue(f.attachment.isOutputReady)
            f.connection.emit(f.bitmap)
            f.attachment.updateResumed(true)
            assertEquals("Later frames and duplicate resume do not create deadlines", 2, f.timer.tasks.size)
            f.attachment.updateResumed(false)
            f.attachment.updateResumed(true)
            val resumed = f.timer.tasks.last()
            assertNotSame(frame, resumed)
            assertEquals(10_000L, resumed.delay)
            f.timer.now = 2_000
            f.attachment.updateResumed(true)
            assertEquals("Duplicate resume cannot replenish this activation", 3, f.timer.tasks.size)
            assertSame(resumed, f.timer.tasks.last())
            f.connection.emit(f.bitmap)
            assertTrue(resumed.canceled)
            f.connection.emit(f.bitmap)
            assertEquals("This protects activations, not individual frames", 3, f.timer.tasks.size)
            f.timer.now = 100_000
            frame.callback()
            resumed.callback()
            assertNull(f.host.failureReason)
            assertEquals(0, f.failures)
        }
    }

    @Test fun timeoutCapturesProgressBeforeRetirementAndALoggerFailureCannotPreventRecovery() {
        Fixture().use { f ->
            f.visible()
            f.renderer().panel("Center_Display")
            f.connection.emit(f.bitmap)
            f.attachment.updateResumed(false)
            f.attachment.updateResumed(true)
            f.output.acceptsPublication = false
            f.connection.emit(f.bitmap)
            f.diagnosticThrows = true
            val task = f.timer.tasks.last()
            f.timer.now = 10_000
            task.callback()
            assertEquals(1, f.diagnostics.size)
            val report = f.diagnostics.single()
            assertTrue(report.contains("reason=FIRST_FRAME_TIMEOUT resumed=true surfaceReady=true surfaceValid=true"))
            assertTrue(report.contains("canvasAttempts=2 canvasAccepted=1 output=UNAVAILABLE"))
            assertTrue(report.contains("phase=WAITING decoded=0"))
            assertEquals(1, f.failures); assertEquals(1, f.connectionFailures)
            task.callback()
            assertEquals(1, f.diagnostics.size)
        }
    }

    @Test fun aSuccessfulConnectionGetsANewVisibleRecoveryBudgetAfterPauseWithoutClosingInBackground() {
        Fixture().use { f ->
            f.visible()
            f.renderer().panel("Center_Display")
            f.connection.emit(f.bitmap)
            val completed = f.dispatchedTimeouts.last()
            f.attachment.updateResumed(false)
            f.timer.now = 500_000
            completed()
            assertNull(f.host.failureReason)
            assertEquals(0, f.connection.closes)
            assertEquals(2, f.timer.tasks.size)
            f.attachment.updateResumed(true)
            val resumed = f.timer.tasks.last()
            assertEquals(10_000L, resumed.delay)
            assertFalse(f.attachment.isOutputReady)
            f.timer.now += 2_000
            f.attachment.updateResumed(false)
            assertTrue(resumed.canceled)
            f.timer.now += 500_000
            resumed.callback()
            assertNull(f.host.failureReason)
            assertEquals(0, f.connection.closes)
            f.attachment.updateResumed(true)
            val remaining = f.timer.tasks.last()
            assertEquals(8_000L, remaining.delay)
            f.timer.now += 8_000
            remaining.callback()
            assertEquals(UltraReferenceFailure.FIRST_FRAME_TIMEOUT, f.host.failureReason)
            assertEquals(1, f.failures)
            assertEquals(1, f.connectionFailures)
            assertEquals(1, f.connection.closes)
        }
    }

    @Test fun anUnusableSurfaceDoesNotStartRecoveryButItsUsableReplacementDoes() {
        Fixture().use { f ->
            f.visible()
            f.renderer().panel("Center_Display")
            f.connection.emit(f.bitmap)
            f.attachment.updateSurface(f.surface, 1920, 719)
            assertEquals(2, f.timer.tasks.size)
            f.timer.now = 500_000
            assertEquals(0, f.failures)
            val texture = SurfaceTexture(0)
            val replacement = Surface(texture)
            try {
                f.attachment.updateSurface(replacement, 1920, 720)
                val deadline = f.timer.tasks.last()
                assertEquals(10_000L, deadline.delay)
                assertFalse(f.attachment.isOutputReady)
                f.attachment.detach(f.surface)
                assertFalse("A stale detach cannot cancel the replacement's budget", deadline.canceled)
                f.connection.emit(f.bitmap)
                assertTrue(f.attachment.isOutputReady)
                assertTrue(deadline.canceled)
                f.timer.now += 100_000
                deadline.callback()
                assertNull(f.host.failureReason)
                assertEquals(0, f.connection.closes)
            } finally { replacement.release(); texture.release() }
        }
    }

    @Test fun anAdoptingActivityMustSubmitItsOwnCanvasAndOldCallbacksCannotRetireItsDeadline() {
        Fixture().use { f ->
            f.visible()
            f.renderer().panel("Center_Display")
            val oldTimeout = f.dispatchedTimeouts.last()
            f.connection.emit(f.bitmap)
            val next = f.host.attachHost { f.failures++ }
            assertEquals(2, f.timer.tasks.size)
            next.updateResumed(true)
            assertEquals(2, f.timer.tasks.size)
            next.updateSurface(f.surface, 1920, 720)
            val deadline = f.timer.tasks.last()
            assertEquals(10_000L, deadline.delay)
            f.attachment.close()
            f.attachment.updateResumed(false)
            f.attachment.detach(f.surface)
            oldTimeout()
            assertFalse(deadline.canceled)
            assertNull(f.host.failureReason)
            f.timer.now = 10_000
            deadline.callback()
            assertEquals(UltraReferenceFailure.FIRST_FRAME_TIMEOUT, f.host.failureReason)
            assertEquals(1, f.failures)
            assertEquals(1, f.connectionFailures)
        }
    }

    @Test fun aCanvasFromBeforeSurfaceReplacementCannotCompleteTheReplacementDeadline() {
        Fixture().use { f ->
            f.visible()
            f.renderer().panel("Center_Display")
            f.connection.emit(f.bitmap)
            val texture = SurfaceTexture(0)
            val replacement = Surface(texture)
            try {
                f.output.beforePublish = {
                    f.output.beforePublish = null
                    f.attachment.updateSurface(replacement, 1920, 720)
                }
                f.connection.emit(f.bitmap)
                val deadline = f.timer.tasks.last()
                assertEquals(3, f.timer.tasks.size)
                assertFalse(f.attachment.isOutputReady)
                assertFalse(deadline.canceled)
                assertEquals(1L, f.host.submittedFrames)
                f.timer.now = 10_000
                deadline.callback()
                assertEquals(UltraReferenceFailure.FIRST_FRAME_TIMEOUT, f.host.failureReason)
                assertEquals(1, f.failures)
            } finally { replacement.release(); texture.release() }
        }
    }

    @Test fun retirementOfAReactivationDeadlineCannotFailTheNextConnectionOrRearmWithoutOne() {
        Fixture().use { f ->
            f.visible()
            val old = f.renderer()
            old.panel("Center_Display")
            f.connection.emit(f.bitmap)
            f.attachment.updateResumed(false)
            f.attachment.updateResumed(true)
            val timeout = f.dispatchedTimeouts.last()
            val deadline = f.timer.tasks.last()
            old.close()
            assertTrue(deadline.canceled)
            f.attachment.updateResumed(false)
            f.attachment.updateResumed(true)
            assertEquals(3, f.timer.tasks.size)
            val next = f.renderer()
            next.panel("Center_Display")
            timeout()
            assertNull(f.host.failureReason)
            f.connection.emit(f.bitmap)
            assertTrue(f.attachment.isOutputReady)
            timeout()
            assertNull(f.host.failureReason)
            assertEquals(0, f.failures)
        }
    }

    @Test fun dispatchedTimeoutCallbacksAreFencedAfterPhaseSuccessAndConnectionReplacement() {
        Fixture().use { f ->
            f.visible()
            f.renderer().panel("Center_Display")
            val oldChannelCallback = f.dispatchedTimeouts[0]
            val oldFrameCallback = f.dispatchedTimeouts[1]
            // Simulate a callback that already left the timer monitor before phase cancellation.
            oldChannelCallback()
            assertNull(f.host.failureReason)
            f.connection.emit(f.bitmap)
            oldFrameCallback()
            assertNull(f.host.failureReason)
            assertTrue(f.attachment.isOutputReady)
            f.renderer().panel("Center_Display")
            oldChannelCallback()
            oldFrameCallback()
            assertNull(f.host.failureReason)
            assertFalse(f.attachment.isOutputReady)
            assertEquals(0, f.failures)
        }
    }

    @Test fun aTimeoutDispatchedBeforePauseDefersRecoveryUntilAUsableResumeWithoutResettingBudget() {
        Fixture().use { f ->
            f.visible()
            f.renderer().panel("Center_Display")
            f.beforeTimeoutDelivery = {
                f.beforeTimeoutDelivery = null
                f.attachment.updateResumed(false)
            }
            f.timer.now = 10_000
            f.timer.tasks.last().callback()
            assertNull(f.host.failureReason)
            assertEquals(0, f.failures)
            f.timer.now += 500_000
            f.attachment.updateResumed(true)
            assertEquals("Pause cannot replenish an already exhausted budget", 0L, f.timer.tasks.last().delay)
            f.timer.tasks.last().callback()
            assertEquals(UltraReferenceFailure.FIRST_FRAME_TIMEOUT, f.host.failureReason)
            assertEquals(1, f.failures)
        }
    }

}
