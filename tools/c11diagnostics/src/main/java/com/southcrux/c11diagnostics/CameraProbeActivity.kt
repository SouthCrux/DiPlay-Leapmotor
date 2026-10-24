package com.southcrux.c11diagnostics

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.AtomicFile
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorCameraSnapshots
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit single capture, separate from the DiPlay and vehicle telemetry processes. */
class CameraProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = workerFactory()
    private val claimed = AtomicBoolean(false)
    private val captureId = java.util.UUID.randomUUID().toString()
    private lateinit var output: TextView
    private var started = false
    @Volatile private var stopped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (CONTENT_PADDING_DP * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply { setText(R.string.camera_title); textSize = 24f })
        content.addView(TextView(this).apply { setText(R.string.camera_explanation) })
        val buttons = mutableListOf<Button>()
        val choices = listOf(LeapmotorCameraSnapshots.Camera.AVM_FRONT to R.string.camera_front,
            LeapmotorCameraSnapshots.Camera.AVM_BACK to R.string.camera_back,
            LeapmotorCameraSnapshots.Camera.AVM_LEFT to R.string.camera_left,
            LeapmotorCameraSnapshots.Camera.AVM_RIGHT to R.string.camera_right)
        for ((camera, title) in choices) {
            val button = Button(this).apply {
                setText(title)
                setOnClickListener {
                    if (!started) {
                        started = true
                        buttons.forEach { it.isEnabled = false }
                        capture(camera)
                    }
                }
            }
            buttons += button; content.addView(button)
        }
        output = TextView(this).apply { setText(R.string.camera_waiting); setTextIsSelectable(true) }
        content.addView(output)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun capture(camera: LeapmotorCameraSnapshots.Camera) {
        output.setText(R.string.camera_capturing)
        val deadline = Runnable {
            if (!claimed.get()) {
                publish(base(camera).put("status", "NATIVE_DEADLINE"))
                terminateOwnProcess()
            }
        }
        main.postDelayed(deadline, 6500)
        worker.execute {
            val report = base(camera)
            try {
                val backend = backendFactory(classLoader)
                if (backend == null) report.put("status", "SDK_UNAVAILABLE") else {
                    val result = LeapmotorCameraSnapshots.capture(backend, camera)
                    report.put("status", result.status.name).put("cleanupFailed", result.cleanupFailed)
                    result.frame?.let { frame ->
                        if (!stopped && !claimed.get() && !Thread.currentThread().isInterrupted) {
                            val bytes = frame.copyBytes()
                            val fileName = "camera-${camera.name.lowercase()}-${java.util.UUID.randomUUID()}.raw"
                            val file = AtomicFile(getFileStreamPath(fileName))
                            val stream = file.startWrite()
                            try { stream.write(bytes); file.finishWrite(stream) }
                            catch (e: Exception) { file.failWrite(stream); throw e }
                            report.put("rawFile", fileName).put("frameBytes", bytes.size)
                                .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes)
                                    .joinToString("") { "%02x".format(it.toInt() and 255) })
                        }
                    }
                }
            } catch (_: Exception) { report.put("status", "CAPTURE_OR_SAVE_ERROR") }
            catch (_: LinkageError) { report.put("status", "SDK_LINK_ERROR") }
            main.post {
                main.removeCallbacks(deadline)
                if (!stopped && !isFinishing && !isDestroyed) publish(report)
            }
        }
    }

    private fun base(camera: LeapmotorCameraSnapshots.Camera) = JSONObject()
        .put("schemaVersion", 1).put("captureId", captureId).put("camera", camera.name).put("cameraIdRequested", camera.oemId)
        .put("captureOrigin", "OEM_SDK_CALLBACK").put("pixelLayoutVerified", false)
        .put("actualGeometryVerified", false).put("actualChannelVerified", false)
        .put("encodedImageAvailable", false).put("requestedWidth", 1920).put("requestedHeight", 1536)
        .put("requestedConversion", "YUV420").put("frameBytes", 0)

    private fun publish(report: JSONObject) {
        if (!claimed.compareAndSet(false, true)) return
        val text = report.toString(2)
        val file = AtomicFile(getFileStreamPath("camera-probe-last.json"))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite(); stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream)
        } catch (_: Exception) {
            file.failWrite(stream)
            report.put("metadataSaveFailed", true)
        }
        output.text = report.toString(2)
    }

    override fun onStop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        terminateOwnProcess()
        super.onStop()
    }
    private fun terminateOwnProcess() {
        if (Application.getProcessName() == "$packageName:cameraprobe") Process.killProcess(Process.myPid())
    }
    companion object {
        private const val CONTENT_PADDING_DP = 20
        internal var backendFactory: (ClassLoader) -> LeapmotorCameraSnapshots.Backend? =
            LeapmotorCameraSnapshots::reflectiveBackend
        internal var workerFactory: () -> ExecutorService = { Executors.newSingleThreadExecutor() }
    }
}
