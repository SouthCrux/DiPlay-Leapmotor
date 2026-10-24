// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.transport.Iap2ThemeAsset
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class UltraReferenceCenterHostTest {
    private class FakeOutput : UltraReferenceCenterHost.Output {
        var surface: Surface? = null
        var closed = false
        var acceptsPublication = true
        val published = mutableListOf<Surface>()
        @Volatile var beforePublish: (() -> Unit)? = null
        override fun attach(surface: Surface) { check(!closed); this.surface = surface }
        override fun detach(surface: Surface) { if (this.surface === surface) this.surface = null }
        override fun publish(bitmap: Bitmap): Boolean {
            check(!closed)
            val destination = surface ?: error("Published without an attached output")
            if (!acceptsPublication) return false
            beforePublish?.invoke()
            published += destination
            return true
        }
        override fun close() { closed = true; surface = null }
    }

    private class FakeConnection(val emit: (Bitmap) -> Unit, val fail: () -> Unit) : UltraReferenceConnection {
        var acceptedOutput = false
        var outputSubmissions = 0
        var outputInvalidations = 0
        val visibility = mutableListOf<Pair<Boolean, Boolean>>()
        val requests = mutableListOf<Boolean>()
        var closed = false
        @Volatile var onVisibility: ((Boolean, Boolean) -> Unit)? = null
        override val videoSink = object : MediaSink {}
        override fun panel(id: String): UiSyncPanelSession = object : UiSyncPanelSession {
            override fun receive(bytes: ByteArray) = emptyList<ByteArray>()
            override fun poll() = emptyList<ByteArray>()
            override fun close() = Unit
        }
        override fun updateHostVisibility(resumed: Boolean, surfaceReady: Boolean) {
            visibility += resumed to surfaceReady
            onVisibility?.invoke(resumed, surfaceReady)
        }
        override fun requestAccessoryScene(request: Boolean) { requests += request }
        override fun outputSubmitted(bitmap: Bitmap) { acceptedOutput = true; outputSubmissions++ }
        override fun outputInvalidated() { acceptedOutput = false; outputInvalidations++ }
        override fun handleTouch(contacts: List<AirPlayContact>, canceled: Boolean): Boolean = acceptedOutput
        override fun close() { closed = true }
    }

    @Test fun interactionFollowsAcceptedCanvasAndCannotSurviveAnInvalidatedActivation() {
        Fixture().use { f ->
            val attachment = f.ready()
            val renderer = f.host.renderer().also { it.panel("Center_Display") }
            val connection = f.latest
            val touch = listOf(AirPlayContact(0,0.5,0.5,true))
            assertFalse(attachment.handleTouch(touch))
            f.output.acceptsPublication = false
            connection.emit(f.bitmap)
            assertEquals(0,connection.outputSubmissions)
            assertFalse(attachment.handleTouch(touch))
            f.output.acceptsPublication = true
            connection.emit(f.bitmap)
            assertEquals(1,connection.outputSubmissions)
            assertTrue(attachment.handleTouch(touch))
            attachment.updateResumed(false)
            assertFalse(attachment.handleTouch(touch))
            assertTrue(connection.outputInvalidations > 0)
            attachment.updateResumed(true)
            assertFalse(attachment.handleTouch(touch))
            f.output.beforePublish = { attachment.updateResumed(false) }
            connection.emit(f.bitmap)
            assertEquals("A Canvas completed after pause cannot activate input",1,connection.outputSubmissions)
            f.output.beforePublish = null
            attachment.updateResumed(true)
            connection.emit(f.bitmap)
            assertTrue(attachment.handleTouch(touch))
            renderer.close()
            assertFalse(attachment.handleTouch(touch))
        }
    }

    private class Fixture(
        build: (((Bitmap) -> Unit, () -> Unit) -> UltraReferenceConnection)? = null,
    ) : Closeable {
        val output = FakeOutput()
        val connections = mutableListOf<FakeConnection>()
        val unusedClosed = AtomicInteger()
        private val surfaces = mutableListOf<Pair<Surface, SurfaceTexture>>()
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val host = UltraReferenceCenterHost(UltraThemeBinding(Iap2ThemeAsset("44C9FA31EC", 985, "19.0"),
            UltraThemeDisplay("Center_Display", 1920, 720, 157)),
            build ?: { publish, failure -> FakeConnection(publish, failure).also { connections += it } },
            { unusedClosed.incrementAndGet() }, output)
        val latest get() = connections.last()
        fun surface(): Surface {
            val texture = SurfaceTexture(0)
            return Surface(texture).also {
                assertTrue("Synthetic surface must start valid", it.isValid)
                surfaces += it to texture
            }
        }
        fun ready(): UltraReferenceCenterHost.Attachment = host.attachHost { error("Unexpected failure") }.also {
            it.updateResumed(true); it.updateSurface(surface(), 1920, 720)
        }
        override fun close() {
            host.close()
            bitmap.recycle()
            surfaces.forEach { (surface, texture) -> surface.release(); texture.release() }
        }
    }

    @Test fun newerConnectionRetiresItsPredecessorAndRejectsOldPublicationFailureAndClose() {
        Fixture().use { fixture ->
            fixture.ready()
            val firstRenderer = fixture.host.renderer().also { it.panel("Center_Display") }
            val first = fixture.latest
            assertEquals(listOf(true), first.requests)
            assertEquals(true to true, first.visibility.last())
            first.emit(fixture.bitmap)
            assertEquals(1, fixture.output.published.size)

            val secondRenderer = fixture.host.renderer().also { it.panel("Center_Display") }
            val second = fixture.latest
            assertTrue(first.closed)
            assertEquals(false to false, first.visibility.last())
            first.emit(fixture.bitmap); first.fail(); firstRenderer.close()
            assertFalse(second.closed)
            assertEquals(true to true, second.visibility.last())
            assertEquals(1, fixture.output.published.size)
            second.emit(fixture.bitmap)
            assertEquals(2, fixture.output.published.size)
            secondRenderer.close()
            second.emit(fixture.bitmap)
            assertEquals(2, fixture.output.published.size)
        }
    }

    @Test fun activityAndSurfaceReplacementIgnoreLateCallbacksFromPreviousOwners() {
        Fixture().use { fixture ->
            val first = fixture.host.attachHost { error("Old Activity callback must be retired") }
            val firstSurface = fixture.surface()
            first.updateResumed(true); first.updateSurface(firstSurface, 1920, 720)
            fixture.host.renderer().panel("Center_Display")
            val connection = fixture.latest
            val second = fixture.host.attachHost { error("Unexpected failure") }
            assertNull(fixture.output.surface)
            assertEquals(false to false, connection.visibility.last())
            val secondSurface = fixture.surface()
            second.updateSurface(secondSurface, 1920, 720); second.updateResumed(true)

            first.updateResumed(false); first.detach(firstSurface)
            first.updateSurface(firstSurface, 1920, 720); first.close()
            assertSame(secondSurface, fixture.output.surface)
            assertEquals(true to true, connection.visibility.last())
            connection.emit(fixture.bitmap)
            assertEquals(listOf(secondSurface), fixture.output.published)

            val replacement = fixture.surface()
            second.updateSurface(replacement, 1920, 720)
            second.detach(secondSurface)
            assertSame(replacement, fixture.output.surface)
            connection.emit(fixture.bitmap)
            assertEquals(listOf(secondSurface, replacement), fixture.output.published)
            second.close()
            assertNull(fixture.output.surface)
            connection.emit(fixture.bitmap)
            assertEquals(2, fixture.output.published.size)
        }
    }

    @Test fun publicationRequiresResumedHostAndValidSurfaceAtExactSelectedBufferDimensions() {
        Fixture().use { fixture ->
            fixture.host.renderer().panel("Center_Display")
            val connection = fixture.latest
            connection.emit(fixture.bitmap)
            val attachment = fixture.host.attachHost { error("Unexpected failure") }
            connection.emit(fixture.bitmap)
            attachment.updateResumed(true)
            connection.emit(fixture.bitmap)
            val surface = fixture.surface()
            attachment.updateSurface(surface, 1920, 719)
            connection.emit(fixture.bitmap)
            assertTrue(fixture.output.published.isEmpty())
            assertEquals(true to false, connection.visibility.last())

            attachment.updateSurface(surface, 1920, 720)
            connection.emit(fixture.bitmap)
            assertEquals(1, fixture.output.published.size)
            attachment.updateResumed(false)
            connection.emit(fixture.bitmap)
            assertEquals(1, fixture.output.published.size)
            attachment.updateResumed(true)
            connection.emit(fixture.bitmap)
            assertEquals(2, fixture.output.published.size)
            attachment.detach(surface)
            connection.emit(fixture.bitmap)
            assertEquals(2, fixture.output.published.size)
            val invalid = fixture.surface().apply { release() }
            attachment.updateSurface(invalid, 1920, 720)
            connection.emit(fixture.bitmap)
            assertEquals(2, fixture.output.published.size)
            assertEquals(true to false, connection.visibility.last())
        }
    }

    @Test fun hostTeardownRetiresConnectionAndUnusedResourcesOnceWithoutReleasingBorrowedSurface() {
        for (createConnection in listOf(false, true)) {
            Fixture().use { fixture ->
                val attachment = fixture.host.attachHost { error("Unexpected failure") }
                val surface = fixture.surface()
                attachment.updateSurface(surface, 1920, 720); attachment.updateResumed(true)
                if (createConnection) fixture.host.renderer()
                fixture.host.close(); fixture.host.close()
                assertTrue(surface.isValid)
                assertTrue(fixture.output.closed)
                assertNull(fixture.output.surface)
                assertEquals(1, fixture.unusedClosed.get())
                fixture.connections.forEach {
                    assertTrue(it.closed); it.emit(fixture.bitmap); it.fail()
                }
                attachment.updateSurface(surface, 1920, 720); attachment.updateResumed(true); attachment.close()
                assertTrue(fixture.output.published.isEmpty())
                assertThrows(IllegalStateException::class.java) { fixture.host.renderer() }
                assertThrows(IllegalStateException::class.java) { fixture.host.attachHost {} }
            }
        }
    }

    @Test fun failureStaysLatchedAndIsReportedToANewActivityWithoutRevivingItsOutput() {
        Fixture().use { fixture ->
            var firstFailures = 0
            val first = fixture.host.attachHost { firstFailures++ }
            first.updateResumed(true); first.updateSurface(fixture.surface(), 1920, 720)
            fixture.host.renderer()
            val failed = fixture.latest
            failed.fail(); failed.fail()
            assertEquals(1, firstFailures)
            assertTrue(failed.closed)
            assertNull(fixture.output.surface)
            failed.emit(fixture.bitmap)
            var newFailures = 0
            val next = fixture.host.attachHost { newFailures++ }
            assertEquals(1, newFailures)
            next.updateResumed(true); next.updateSurface(fixture.surface(), 1920, 720)
            first.close(); first.updateResumed(true)
            failed.emit(fixture.bitmap)
            assertTrue(fixture.output.published.isEmpty())
            assertNull(fixture.output.surface)
            assertThrows(IllegalStateException::class.java) { fixture.host.renderer() }
            fixture.host.close()
            assertEquals(1, fixture.unusedClosed.get())
        }
    }

    @Test fun failedConstructionRemainsFailedAndUnusedResourcesAreReleasedByControllerClose() {
        val chosen = IllegalStateException("Synthetic build failure")
        Fixture { _, _ -> throw chosen }.use { fixture ->
            var failures = 0
            fixture.host.attachHost { failures++ }
            assertSame(chosen, assertThrows(IllegalStateException::class.java) { fixture.host.renderer() })
            assertEquals(1, failures)
            assertEquals(0, fixture.unusedClosed.get())
            assertThrows(IllegalStateException::class.java) { fixture.host.renderer() }
            fixture.host.close()
            assertEquals(1, fixture.unusedClosed.get())
        }
    }

    @Test fun controllerCloseDuringConstructionRejectsAndClosesTheLateCandidate() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val candidate = AtomicReference<FakeConnection>()
        val caught = AtomicReference<Throwable>()
        Fixture { publish, failure ->
            FakeConnection(publish, failure).also {
                candidate.set(it); entered.countDown()
                check(release.await(3, TimeUnit.SECONDS)) { "Test construction barrier timed out" }
            }
        }.use { fixture ->
            fixture.ready()
            val building = thread(name = "ultra-reference-test-build") {
                try { fixture.host.renderer() } catch (failure: Throwable) { caught.set(failure) }
            }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                fixture.host.close()
                candidate.get().emit(fixture.bitmap); candidate.get().fail()
            } finally { release.countDown(); building.join(3_000) }
            assertFalse("Construction thread must finish", building.isAlive)
            assertTrue(caught.get() is IllegalStateException)
            assertTrue(candidate.get().closed)
            assertTrue(fixture.output.published.isEmpty())
            assertEquals(1, fixture.unusedClosed.get())
        }
    }

    @Test fun outputReadinessRequiresTheProtocolPanelAndSuccessfulCanvasSubmission() {
        Fixture().use { fixture ->
            val attachment = fixture.ready()
            val notices = mutableListOf<Boolean>()
            attachment.setOutputListener { notices += attachment.isOutputReady }
            val renderer = fixture.host.renderer()
            val connection = fixture.latest
            assertFalse(attachment.isOutputReady)
            connection.emit(fixture.bitmap)
            assertFalse(attachment.isOutputReady)
            assertTrue(fixture.output.published.isEmpty())
            assertEquals(0L, fixture.host.submittedFrames)

            renderer.panel("Center_Display")
            fixture.output.acceptsPublication = false
            connection.emit(fixture.bitmap)
            assertFalse(attachment.isOutputReady)
            assertEquals(0L, fixture.host.submittedFrames)
            fixture.output.acceptsPublication = true
            connection.emit(fixture.bitmap)
            assertTrue(attachment.isOutputReady)
            assertEquals(true, notices.last())
            assertEquals(1L, fixture.host.submittedFrames)

            attachment.updateResumed(false)
            assertFalse(attachment.isOutputReady)
            assertEquals(false, notices.last())
            attachment.updateResumed(true)
            assertFalse("Resume does not establish a new output submission", attachment.isOutputReady)
            connection.emit(fixture.bitmap)
            assertTrue(attachment.isOutputReady)
            attachment.detach(checkNotNull(fixture.output.surface))
            assertFalse(attachment.isOutputReady)
            assertEquals(false, notices.last())
            attachment.updateSurface(fixture.surface(), 1920, 720)
            assertFalse(attachment.isOutputReady)
            connection.emit(fixture.bitmap)
            assertTrue(attachment.isOutputReady)
        }
    }

    @Test fun outputReadinessDoesNotLeakAcrossConnectionOrActivityOwnership() {
        Fixture().use { fixture ->
            val firstAttachment = fixture.ready()
            val firstNotices = mutableListOf<Boolean>()
            firstAttachment.setOutputListener { firstNotices += firstAttachment.isOutputReady }
            val oldRenderer = fixture.host.renderer().also { it.panel("Center_Display") }
            val old = fixture.latest
            old.emit(fixture.bitmap)
            assertTrue(firstAttachment.isOutputReady)

            val nextRenderer = fixture.host.renderer()
            val next = fixture.latest
            assertFalse(firstAttachment.isOutputReady)
            val afterReplacement = firstNotices.size
            old.emit(fixture.bitmap); oldRenderer.close()
            assertEquals(afterReplacement, firstNotices.size)
            assertFalse(firstAttachment.isOutputReady)
            nextRenderer.panel("Center_Display")
            next.emit(fixture.bitmap)
            assertTrue(firstAttachment.isOutputReady)

            val nextAttachment = fixture.host.attachHost { error("Unexpected failure") }
            val nextNotices = mutableListOf<Boolean>()
            nextAttachment.setOutputListener { nextNotices += nextAttachment.isOutputReady }
            nextAttachment.updateResumed(true)
            nextAttachment.updateSurface(fixture.surface(), 1920, 720)
            assertFalse("Host counter is historical, not this Activity's readiness", nextAttachment.isOutputReady)
            val oldNoticeCount = firstNotices.size
            firstAttachment.updateResumed(true); firstAttachment.close()
            old.emit(fixture.bitmap)
            assertFalse(nextAttachment.isOutputReady)
            next.emit(fixture.bitmap)
            assertTrue(nextAttachment.isOutputReady)
            assertEquals(true, nextNotices.last())
            assertEquals(oldNoticeCount, firstNotices.size)
            nextRenderer.close()
            assertFalse(nextAttachment.isOutputReady)
            assertEquals(false, nextNotices.last())
        }
    }

    @Test fun outputHintsRunOutsideTheHostLockAndCannotBreakDecoderOwnership() {
        Fixture().use { fixture ->
            val attachment = fixture.ready()
            val monitor = UltraReferenceCenterHost::class.java.getDeclaredField("lock")
                .apply { isAccessible = true }.get(fixture.host)
            val heldLocks = mutableListOf<Boolean>()
            attachment.setOutputListener {
                heldLocks += Thread.holdsLock(monitor)
                error("Synthetic UI observer failure")
            }
            fixture.host.renderer().panel("Center_Display")
            fixture.latest.emit(fixture.bitmap)
            assertTrue(attachment.isOutputReady)
            assertFalse(fixture.latest.closed)
            attachment.updateResumed(false)
            assertFalse(attachment.isOutputReady)
            assertTrue(heldLocks.isNotEmpty())
            assertTrue("UI hints must not run inside the host ownership lock", heldLocks.none { it })
        }
    }

    @Test fun slowCanvasDoesNotHoldHostStateAndPauseResumeRejectsItsOldReadiness() {
        Fixture().use { fixture ->
            val attachment = fixture.ready()
            fixture.host.renderer().panel("Center_Display")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val caught = AtomicReference<Throwable>()
            val monitor = UltraReferenceCenterHost::class.java.getDeclaredField("lock")
                .apply { isAccessible = true }.get(fixture.host)
            fixture.output.beforePublish = {
                assertFalse("Canvas must not retain the host-state monitor", Thread.holdsLock(monitor))
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS)) { "Canvas test barrier timed out" }
            }
            val publisher = thread(name = "ultra-reference-test-canvas") {
                try { fixture.latest.emit(fixture.bitmap) } catch (failure: Throwable) { caught.set(failure) }
            }
            var lifecycle: Thread? = null
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                lifecycle = thread(name = "ultra-reference-test-lifecycle") {
                    try {
                        attachment.updateResumed(false)
                        assertFalse(attachment.isOutputReady)
                        assertNull(fixture.host.failureReason)
                        attachment.updateResumed(true)
                        assertFalse(attachment.isOutputReady)
                    } catch (failure: Throwable) { caught.set(failure) }
                    finally { completed.countDown() }
                }
                assertTrue("Host lifecycle/readiness must not wait for Canvas", completed.await(1, TimeUnit.SECONDS))
                assertEquals(0L, fixture.host.submittedFrames)
            } finally {
                release.countDown()
                publisher.join(3_000)
                lifecycle?.join(3_000)
            }
            assertNull(caught.get())
            assertFalse(publisher.isAlive)
            assertFalse("Old Canvas cannot establish readiness after pause/resume", attachment.isOutputReady)
            assertEquals(0L, fixture.host.submittedFrames)
            fixture.output.beforePublish = null
            fixture.latest.emit(fixture.bitmap)
            assertTrue(attachment.isOutputReady)
            assertEquals(1L, fixture.host.submittedFrames)
        }
    }

    @Test fun surfaceReplacementRetiresReadinessBeforeWaitingForCanvasAndPreservesBorrowedSurfaces() {
        Fixture().use { fixture ->
            val attachment = fixture.ready()
            fixture.host.renderer().panel("Center_Display")
            val original = checkNotNull(fixture.output.surface)
            val replacement = fixture.surface()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val invalidated = CountDownLatch(1)
            val caught = AtomicReference<Throwable>()
            fixture.output.beforePublish = {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS)) { "Canvas test barrier timed out" }
            }
            val connection = fixture.latest
            val publisher = thread(name = "ultra-reference-test-canvas") {
                try { connection.emit(fixture.bitmap) } catch (failure: Throwable) { caught.set(failure) }
            }
            var changing: Thread? = null
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                connection.onVisibility = { _, ready -> if (!ready) invalidated.countDown() }
                changing = thread(name = "ultra-reference-test-surface") {
                    try { attachment.updateSurface(replacement, 1920, 720) }
                    catch (failure: Throwable) { caught.set(failure) }
                }
                assertTrue("Surface replacement must invalidate state before waiting for Canvas",
                    invalidated.await(1, TimeUnit.SECONDS))
                assertFalse(attachment.isOutputReady)
                assertTrue("Surface ownership remains serialized with Canvas", changing.isAlive)
                assertSame(original, fixture.output.surface)
            } finally {
                release.countDown()
                publisher.join(3_000)
                changing?.join(3_000)
            }
            assertNull(caught.get())
            assertFalse(publisher.isAlive)
            assertFalse(changing!!.isAlive)
            assertSame(replacement, fixture.output.surface)
            assertTrue(original.isValid)
            assertTrue(replacement.isValid)
            assertFalse(attachment.isOutputReady)
            assertEquals(0L, fixture.host.submittedFrames)
            fixture.output.beforePublish = null
            connection.emit(fixture.bitmap)
            assertTrue(attachment.isOutputReady)
            assertSame(replacement, fixture.output.published.last())
            attachment.detach(original)
            assertSame("A late old-surface detach cannot remove its successor", replacement, fixture.output.surface)
        }
    }

    @Test fun connectionReplacementDuringCanvasCannotPublishReadinessIntoTheNewEpoch() {
        Fixture().use { fixture ->
            val attachment = fixture.ready()
            fixture.host.renderer().panel("Center_Display")
            val old = fixture.latest
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val caught = AtomicReference<Throwable>()
            fixture.output.beforePublish = {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS)) { "Canvas test barrier timed out" }
            }
            val publisher = thread(name = "ultra-reference-test-old-canvas") {
                try { old.emit(fixture.bitmap) } catch (failure: Throwable) { caught.set(failure) }
            }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                fixture.host.renderer().panel("Center_Display")
                assertFalse(attachment.isOutputReady)
            } finally { release.countDown(); publisher.join(3_000) }
            assertNull(caught.get())
            assertFalse(publisher.isAlive)
            assertFalse(attachment.isOutputReady)
            assertEquals(0L, fixture.host.submittedFrames)
            fixture.output.beforePublish = null
            fixture.latest.emit(fixture.bitmap)
            assertTrue(attachment.isOutputReady)
            assertEquals(1L, fixture.host.submittedFrames)
        }
    }

    @Test fun failureStateIsReadableWhileOutputRetirementWaitsForInFlightCanvas() {
        Fixture().use { fixture ->
            val failures = AtomicInteger()
            val attachment = fixture.host.attachHost { failures.incrementAndGet() }
            val surface = fixture.surface()
            attachment.updateResumed(true)
            attachment.updateSurface(surface, 1920, 720)
            fixture.host.renderer().panel("Center_Display")
            val connection = fixture.latest
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val invalidated = CountDownLatch(1)
            val caught = AtomicReference<Throwable>()
            fixture.output.beforePublish = {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS)) { "Canvas test barrier timed out" }
            }
            val publisher = thread(name = "ultra-reference-test-failing-canvas") {
                try { connection.emit(fixture.bitmap) } catch (failure: Throwable) { caught.set(failure) }
            }
            var failing: Thread? = null
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                connection.onVisibility = { _, ready -> if (!ready) invalidated.countDown() }
                failing = thread(name = "ultra-reference-test-failure") {
                    try { connection.fail() } catch (failure: Throwable) { caught.set(failure) }
                }
                assertTrue(invalidated.await(1, TimeUnit.SECONDS))
                assertEquals(UltraReferenceFailure.RENDERER_FAILURE, fixture.host.failureReason)
                assertFalse(attachment.isOutputReady)
                assertTrue("Output retirement must await its borrowed Canvas", failing.isAlive)
                assertSame(surface, fixture.output.surface)
            } finally {
                release.countDown()
                publisher.join(3_000)
                failing?.join(3_000)
            }
            assertNull(caught.get())
            assertFalse(publisher.isAlive)
            assertFalse(failing!!.isAlive)
            assertNull(fixture.output.surface)
            assertTrue(surface.isValid)
            assertEquals(1, failures.get())
            assertEquals(0L, fixture.host.submittedFrames)
            assertFalse(attachment.isOutputReady)
        }
    }

    @Test fun closingUiSyncDuringAnOpenConnectionInvalidatesCanvasReadinessAndReportsChannelFailure() {
        Fixture().use { fixture ->
            var hostFailures = 0
            var connectionFailures = 0
            val attachment = fixture.host.attachHost { hostFailures++ }
            attachment.updateSurface(fixture.surface(), 1920, 720); attachment.updateResumed(true)
            val notices = mutableListOf<Boolean>()
            attachment.setOutputListener { notices += attachment.isOutputReady }
            val renderer = fixture.host.renderer(onConnectionFailure = { connectionFailures++ })
            val panel = renderer.panel("Center_Display")
            val connection = fixture.latest
            connection.emit(fixture.bitmap)
            assertTrue(attachment.isOutputReady)

            panel.close()

            assertFalse(attachment.isOutputReady)
            assertEquals(false, notices.last())
            assertTrue(connection.closed)
            assertEquals(UltraReferenceFailure.UI_SYNC_CHANNEL_CLOSED, fixture.host.failureReason)
            assertEquals(1, hostFailures)
            assertEquals(1, connectionFailures)
            connection.emit(fixture.bitmap)
            assertFalse(attachment.isOutputReady)
        }
    }

    @Test fun closingUiSyncDuringConnectionTeardownInvalidatesCanvasWithoutRequestingFallback() {
        Fixture().use { fixture ->
            var hostFailures = 0
            var connectionFailures = 0
            val attachment = fixture.host.attachHost { hostFailures++ }
            attachment.updateSurface(fixture.surface(), 1920, 720); attachment.updateResumed(true)
            val notices = mutableListOf<Boolean>()
            attachment.setOutputListener { notices += attachment.isOutputReady }
            val renderer = fixture.host.renderer(isConnectionOpen = { false },
                onConnectionFailure = { connectionFailures++ })
            val panel = renderer.panel("Center_Display")
            val connection = fixture.latest
            connection.emit(fixture.bitmap)
            assertTrue(attachment.isOutputReady)

            panel.close()

            assertFalse(attachment.isOutputReady)
            assertEquals(false, notices.last())
            assertTrue(connection.closed)
            assertNull(fixture.host.failureReason)
            assertEquals(0, hostFailures)
            assertEquals(0, connectionFailures)
            connection.emit(fixture.bitmap)
            assertFalse(attachment.isOutputReady)
        }
    }
}
