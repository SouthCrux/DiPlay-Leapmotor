package com.shilapi.xcertplay.vehicle.leapmotor

import org.junit.Assert.*
import org.junit.Test

class LeapmotorCameraSnapshotsTest {
    private class Fake : LeapmotorCameraSnapshots.Backend {
        val calls = mutableListOf<String>()
        var listener: ((ByteArray?) -> Unit)? = null
        var failAt: String? = null
        var onStart: (() -> Unit)? = null
        var onCallbackRegistration: (() -> Unit)? = null
        var cleanupFails = false
        fun call(name: String): Boolean { calls += name; return failAt != name }
        override fun initYuv420() = call("init")
        override fun setParams(width: Int, height: Int, fps: Float) = call("params:$width:$height:$fps")
        override fun setCallback(callback: (ByteArray?) -> Unit): Boolean {
            listener = callback; onCallbackRegistration?.invoke(); return call("callback")
        }
        override fun open(cameraId: Int) = call("open:$cameraId")
        override fun start(): Boolean { onStart?.invoke(); return call("start") }
        override fun stop(): Boolean { call("stop"); if (cleanupFails) error("cleanup"); return true }
        override fun close() = call("close")
        override fun uninit() = call("uninit")
    }
    private val front = LeapmotorCameraSnapshots.Camera.AVM_FRONT

    @Test fun oneShotCopiesOnlyOneFrameAndReleasesItsSessionWithoutClaimingAnImageFormat() {
        val fake = Fake()
        val source = byteArrayOf(1, 2, 3, 4)
        fake.onStart = { fake.listener!!(source); source[0] = 9; fake.listener!!(byteArrayOf(8)) }
        val result = LeapmotorCameraSnapshots.capture(fake, front)
        assertEquals(LeapmotorCameraSnapshots.Status.FRAME_BYTES_CAPTURED, result.status)
        val frame = checkNotNull(result.frame)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), frame.copyBytes())
        frame.copyBytes()[0] = 7
        assertEquals(1, frame.copyBytes()[0].toInt())
        assertFalse(frame.pixelLayoutVerified); assertFalse(frame.actualGeometryVerified)
        assertFalse(frame.actualChannelVerified)
        assertEquals(listOf("init", "params:1920:1536:5.0", "callback", "open:2", "start", "stop", "close", "uninit"), fake.calls)
        fake.listener!!(byteArrayOf(9))
        assertEquals(4, frame.byteCount)
    }

    @Test fun failedOpenDoesNotCloseOrStopAnUnopenedCamera() {
        val fake = Fake().apply { failAt = "open:2" }
        assertEquals(LeapmotorCameraSnapshots.Status.OPEN_FAILED, LeapmotorCameraSnapshots.capture(fake, front).status)
        assertFalse(fake.calls.contains("stop")); assertFalse(fake.calls.contains("close"))
        assertEquals("uninit", fake.calls.last())
        val failedInit = Fake().apply { failAt = "init" }
        assertEquals(LeapmotorCameraSnapshots.Status.INIT_FAILED, LeapmotorCameraSnapshots.capture(failedInit, front).status)
        assertEquals(listOf("init"), failedInit.calls)
    }

    @Test fun failedStartClosesOnlyTheOpenedSessionAndCleanupErrorsAreReported() {
        val fake = Fake().apply { failAt = "start" }
        assertEquals(LeapmotorCameraSnapshots.Status.START_FAILED, LeapmotorCameraSnapshots.capture(fake, front).status)
        assertFalse(fake.calls.contains("stop")); assertEquals(listOf("close", "uninit"), fake.calls.takeLast(2))
        val cleanup = Fake().apply { cleanupFails = true; onStart = { listener!!(byteArrayOf(1)) } }
        val result = LeapmotorCameraSnapshots.capture(cleanup, front)
        assertTrue(result.cleanupFailed)
        assertEquals(listOf("stop", "close", "uninit"), cleanup.calls.takeLast(3))
    }

    @Test fun earlyFramesAreIgnoredAndTimeoutCannotBeRevivedByLateCallbacks() {
        val fake = Fake().apply { onCallbackRegistration = { listener!!(byteArrayOf(3)) } }
        val result = LeapmotorCameraSnapshots.capture(fake, front, timeoutMillis = 1)
        assertEquals(LeapmotorCameraSnapshots.Status.TIMEOUT, result.status)
        fake.listener!!(byteArrayOf(4))
        assertNull(result.frame)
    }

    @Test fun oversizedEmptyAndNullFramesAreRejectedWithoutPublishingPreviousData() {
        for (payload in listOf(null, byteArrayOf(), ByteArray(16 * 1024 * 1024 + 1))) {
            val fake = Fake().apply { onStart = { listener!!(payload) } }
            val result = LeapmotorCameraSnapshots.capture(fake, front)
            assertEquals(LeapmotorCameraSnapshots.Status.INVALID_FRAME, result.status)
            assertNull(result.frame)
        }
    }

    @Test fun concurrentRequestsAreRejectedAndOnlyExteriorAvmChannelsAreExposed() {
        val second = Fake()
        val first = Fake().apply { onStart = {
            assertEquals(LeapmotorCameraSnapshots.Status.BUSY, LeapmotorCameraSnapshots.capture(second, front).status)
            listener!!(byteArrayOf(1))
        } }
        assertEquals(LeapmotorCameraSnapshots.Status.FRAME_BYTES_CAPTURED, LeapmotorCameraSnapshots.capture(first, front).status)
        assertTrue(second.calls.isEmpty())
        assertEquals(setOf(2, 3, 4, 5), LeapmotorCameraSnapshots.Camera.entries.map { it.oemId }.toSet())
    }

    @Test fun reflectiveBridgeUsesTheInspectedSdkSignaturesAndWaitsForExplicitCapture() {
        val manager = lmos.framework.core.cam.CamManager.getInstance()
        manager.calls.clear()
        val backend = checkNotNull(LeapmotorCameraSnapshots.reflectiveBackend(javaClass.classLoader!!))
        assertTrue(manager.calls.isEmpty())
        val result = LeapmotorCameraSnapshots.capture(backend, LeapmotorCameraSnapshots.Camera.AVM_BACK)
        assertEquals(LeapmotorCameraSnapshots.Status.FRAME_BYTES_CAPTURED, result.status)
        assertArrayEquals(byteArrayOf(1, 2, 3), result.frame!!.copyBytes())
        assertTrue(manager.calls.contains("init:true")); assertTrue(manager.calls.contains("open:3"))
        assertEquals(listOf("stop", "close", "uninit"), manager.calls.takeLast(3))
    }

    @Test fun missingVendorSdkReturnsUnavailableInsteadOfStartingFallbackCamera() {
        var resolutionAttempted = false
        val loader = object : ClassLoader(null) {
            override fun loadClass(name: String): Class<*> { resolutionAttempted = true; throw ClassNotFoundException(name) }
        }
        assertNull(LeapmotorCameraSnapshots.reflectiveBackend(loader))
        assertTrue(resolutionAttempted) // Class resolution attempted; no constructor/native method exists to call.
    }
}
