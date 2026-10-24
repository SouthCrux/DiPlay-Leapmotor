package com.southcrux.c11diagnostics

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorCarPropertyReader
import com.shilapi.xcertplay.vehicle.leapmotor.ReflectiveCarPropertyAccess
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** No automatic scans, background services, subscriptions or vehicle controls. */
class DiagnosticActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val workerBusy = AtomicBoolean(false)
    private lateinit var connection: DiagnosticCarConnection
    private lateinit var output: TextView
    private lateinit var scan: Button
    private var generation = 0
    private var report: String? = null
    private var lastOemReportModified = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connection = DiagnosticCarConnection(applicationContext)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (BLOCK_PADDING_DP * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun label(resource: Int) = TextView(this).apply { setText(resource); content.addView(this) }
        fun button(resource: Int, action: () -> Unit) = Button(this).apply {
            setText(resource); setOnClickListener { action() }; content.addView(this)
        }
        label(R.string.app_title).textSize = 24f
        label(R.string.explanation)
        scan = button(R.string.scan) { collect() }
        label(R.string.energy_explanation)
        button(R.string.energy_permission) {
            if (checkSelfPermission(ENERGY_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                generation++
                connection.close()
                report = null
                output.setText(R.string.permission_result)
                requestPermissions(arrayOf(ENERGY_PERMISSION), 1)
            }
        }
        button(R.string.share) {
            report?.let {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, it), getString(R.string.share_title)))
            }
        }
        button(R.string.camera_open) { startActivity(Intent(this, CameraProbeActivity::class.java)) }
        button(R.string.wiper_open) { startActivity(Intent(this, OemProbeActivity::class.java)
            .putExtra("probe_kind", "WIPERS")) }
        button(R.string.oem_open) { startActivity(Intent(this, OemProbeActivity::class.java)) }
        output = label(R.string.scan_first).apply { setTextIsSelectable(true) }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun collect() {
        if (workerBusy.get()) return
        val token = ++generation
        report = null
        output.setText(R.string.waiting)
        scan.isEnabled = false
        val result = DiagnosticReport.baseline(this)
        fun complete(status: String) {
            if (token != generation) return
            result.put("carConnection", status)
            report = result.toString(2)
            output.text = report
            scan.isEnabled = !workerBusy.get()
        }
        connection.open { car, status ->
            if (token != generation) return@open
            if (car == null) {
                // Invalidate in-flight work if the existing service disappears mid-read.
                complete(status)
                generation++
                return@open
            }
            if (!workerBusy.compareAndSet(false, true)) return@open
            val deadline = Runnable {
                if (token == generation) {
                    connection.close()
                    complete("READ_TIMEOUT")
                    generation++
                }
            }
            main.postDelayed(deadline, 8_000)
            worker.execute {
                var finalStatus = status
                var ev: org.json.JSONArray? = null
                try {
                    val manager = car.propertyManager()
                    ev = DiagnosticReport.ev(LeapmotorCarPropertyReader(ReflectiveCarPropertyAccess(manager)).read())
                } catch (_: SecurityException) {
                    finalStatus = "API_ACCESS_DENIED"
                } catch (_: ReflectiveOperationException) {
                    finalStatus = "API_NOT_AVAILABLE_OR_BLOCKED"
                } catch (_: LinkageError) {
                    finalStatus = "CAR_LIBRARY_NOT_AVAILABLE"
                } catch (_: Exception) {
                    finalStatus = "READ_FAILED"
                } finally {
                    workerBusy.set(false)
                    main.post {
                        main.removeCallbacks(deadline)
                        if (token == generation) {
                            connection.close()
                            ev?.let { result.put("ev", it) }
                            complete(finalStatus)
                        } else if (!isFinishing && !isDestroyed) {
                            scan.isEnabled = true
                        }
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Existing results are no longer evidence of current permissions. Never auto-scan.
        generation++
        connection.close()
        report = null
        scan.isEnabled = !workerBusy.get()
        output.setText(R.string.permission_result)
    }

    override fun onResume() {
        super.onResume()
        val file = getFileStreamPath("oem-probe-last.json")
        if (file.exists() && file.lastModified() > lastOemReportModified) {
            try {
                val text = file.readText(Charsets.UTF_8)
                if (JSONObject(text).has("oem")) {
                    report = text
                    output.text = text
                    lastOemReportModified = file.lastModified()
                }
            } catch (_: java.io.IOException) { } catch (_: org.json.JSONException) { }
        }
    }

    override fun onStop() {
        generation++
        connection.close()
        scan.isEnabled = !workerBusy.get()
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object { const val BLOCK_PADDING_DP = 16 }
}
