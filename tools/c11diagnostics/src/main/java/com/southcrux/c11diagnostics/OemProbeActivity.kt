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
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit, single-use native probe. The manifest puts this screen in its own disposable process. */
class OemProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView
    private var isWiperProbe = false
    private var completed = false
    private var started = false
    private val reportClaimed = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isWiperProbe = intent.getStringExtra("probe_kind") == "WIPERS"
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (BLOCK_PADDING_DP * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply { setText(if (isWiperProbe) R.string.wiper_title else R.string.oem_title); textSize = 24f })
        content.addView(TextView(this).apply { setText(if (isWiperProbe) R.string.wiper_explanation else R.string.oem_explanation) })
        val buttons = mutableListOf<Button>()
        for (seconds in listOf(5, 30)) {
            buttons += Button(this).apply {
                text = getString(R.string.oem_scan_seconds, seconds)
                setOnClickListener {
                    if (!started) {
                        started = true
                        buttons.forEach { it.isEnabled = false }
                        collect(seconds)
                    }
                }
                content.addView(this)
            }
        }
        output = TextView(this).apply { setText(R.string.oem_scan_first); setTextIsSelectable(true) }
        content.addView(output)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun collect(seconds: Int) {
        require(seconds == 5 || seconds == 30)
        output.text = getString(R.string.oem_waiting_seconds, seconds)
        val baseline = DiagnosticReport.baseline(this).put("requestedOemWindowSeconds", seconds).toString()
        val runner = if (isWiperProbe) OemProbeRunner(::NativeWiperCandidateClient,
            WiperCandidateSamples()) else OemProbeRunner()
        val deadline = Runnable {
            if (!completed) {
                publish(JSONObject(baseline).put("oem", runner.samples.finish("NATIVE_TIMEOUT")))
                terminateProbeProcess()
            }
        }
        main.postDelayed(deadline, TimeUnit.SECONDS.toMillis(seconds + 4L))
        worker.execute {
            val result = runner.run(
                waitForWindow = { CountDownLatch(1).await(seconds.toLong(), TimeUnit.SECONDS); Unit },
                beforeCleanup = { save(JSONObject(baseline).put("oem", it).toString(2)) },
            )
            main.post {
                if (!completed && !isFinishing && !isDestroyed) {
                    completed = true
                    main.removeCallbacks(deadline)
                    publish(JSONObject(baseline).put("oem", result))
                }
            }
        }
    }

    private fun publish(result: JSONObject) {
        val text = result.toString(2)
        save(text)
        output.text = text
    }

    private fun save(text: String) {
        // Explicit finite allowlists only; no VIN/location/network identifiers.
        if (!reportClaimed.compareAndSet(false, true)) return
        val file = AtomicFile(getFileStreamPath(if (isWiperProbe) "wiper-probe-last.json" else "oem-probe-last.json"))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (_: java.io.IOException) {
            file.failWrite(stream)
            // The report shown on screen, when the process remains alive, still has the result.
        }
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        // OS closure of this process's descriptors also bounds a stuck native start/stop/reconnect.
        terminateProbeProcess()
    }

    private fun terminateProbeProcess() {
        // Never kill the ordinary diagnostics process if the manifest is accidentally changed.
        if (Application.getProcessName() == "$packageName:oemprobe") Process.killProcess(Process.myPid())
    }

    private companion object { const val BLOCK_PADDING_DP = 16 }
}
