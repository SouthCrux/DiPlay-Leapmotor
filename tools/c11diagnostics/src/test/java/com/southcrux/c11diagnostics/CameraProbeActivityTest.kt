package com.southcrux.c11diagnostics

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorCameraSnapshots
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class CameraProbeActivityTest {
    private val originalBackend = CameraProbeActivity.backendFactory
    private val originalWorker = CameraProbeActivity.workerFactory
    @Before fun setup() { CameraProbeActivity.workerFactory = { DirectExecutor() } }
    @After fun restore() {
        CameraProbeActivity.backendFactory = originalBackend
        CameraProbeActivity.workerFactory = originalWorker
    }
    private class DirectExecutor : AbstractExecutorService() {
        private var closed = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { closed = true }
        override fun shutdownNow(): MutableList<Runnable> { closed = true; return mutableListOf() }
        override fun isShutdown() = closed
        override fun isTerminated() = closed
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = closed
    }
    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun openingScreenDoesNotDiscoverOrOpenAnyCameraUntilExplicitSelection() {
        var requests = 0
        CameraProbeActivity.backendFactory = { requests++; null }
        val controller = Robolectric.buildActivity(CameraProbeActivity::class.java).setup()
        val activity = controller.get()
        val choices = buttons(activity.findViewById(android.R.id.content))
        assertEquals(4, choices.size)
        assertEquals(0, requests)
        choices.first().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, requests)
        assertTrue(choices.all { !it.isEnabled })
        val report = JSONObject(activity.getFileStreamPath("camera-probe-last.json").readText())
        assertEquals("SDK_UNAVAILABLE", report.getString("status"))
        assertEquals(36, report.getString("captureId").length)
        assertFalse(report.getBoolean("encodedImageAvailable"))
        assertFalse(report.has("rawFile"))
        controller.pause().stop().destroy()
    }

    @Test fun explicitCallbackCaptureArchivesOriginalBytesWithoutClaimingJpegOrVerifiedGeometry() {
        val calls = mutableListOf<String>()
        val bytes = byteArrayOf(9, 8, 7, 6)
        CameraProbeActivity.backendFactory = {
            object : LeapmotorCameraSnapshots.Backend {
                lateinit var listener: (ByteArray?) -> Unit
                override fun initYuv420() = true
                override fun setParams(width: Int, height: Int, fps: Float) = true
                override fun setCallback(callback: (ByteArray?) -> Unit): Boolean { listener = callback; return true }
                override fun open(cameraId: Int): Boolean { calls += "open:$cameraId"; return true }
                override fun start(): Boolean { listener(bytes); return true }
                override fun stop(): Boolean { calls += "stop"; return true }
                override fun close(): Boolean { calls += "close"; return true }
                override fun uninit(): Boolean { calls += "uninit"; return true }
            }
        }
        val controller = Robolectric.buildActivity(CameraProbeActivity::class.java).setup()
        val activity = controller.get()
        buttons(activity.findViewById(android.R.id.content))[1].performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val report = JSONObject(activity.getFileStreamPath("camera-probe-last.json").readText())
        assertEquals("FRAME_BYTES_CAPTURED", report.getString("status"))
        assertEquals("AVM_BACK", report.getString("camera"))
        assertEquals(4, report.getInt("frameBytes"))
        assertEquals(64, report.getString("sha256").length)
        assertFalse(report.getBoolean("pixelLayoutVerified"))
        assertFalse(report.getBoolean("actualGeometryVerified"))
        assertFalse(report.getBoolean("encodedImageAvailable"))
        assertArrayEquals(bytes, activity.getFileStreamPath(report.getString("rawFile")).readBytes())
        assertEquals(listOf("open:3", "stop", "close", "uninit"), calls)
        controller.pause().stop().destroy()
    }
}
