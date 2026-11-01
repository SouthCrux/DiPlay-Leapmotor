package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.homeautomation.C11GateButtonGuard
import com.shilapi.xcertplay.homeautomation.C11GateFocusPolicy
import com.shilapi.xcertplay.homeautomation.RefossGarageClient
import com.shilapi.xcertplay.homeautomation.RefossLocalHttp
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Private opt-in C11 installation. Uses home Wi-Fi directly; no cloud or internet requirement. */
internal class C11RefossGate internal constructor(
    private val app: Context,
    private val config: Config,
    private val session: () -> Any?,
    private val allowed: () -> Boolean,
    private val changed: () -> Unit,
    private val result: (Boolean) -> Unit,
    private val restore: () -> Unit,
    private val clientFactory: (() -> RefossGarageClient)? = null,
) {
    internal class Config(val address: InetAddress, val key: String, val deviceId: String)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "c11-refoss-gate").apply { isDaemon = true } }
    private val guard = C11GateButtonGuard().apply {
        reset(SystemClock.elapsedRealtime(), LeapmotorTelemetry.customButtonIdleKnown())
    }
    private var visible = false
    private var pausedAt = -1L
    private var closed = false
    private var epoch = 0
    private var readPending = false
    private var actionPending = false
    private var verifiedAt = -1L
    private var failure: Status? = null
    private var lastDiagnostic: String? = null
    enum class Status { WAITING_CARPLAY, CHECKING, WAITING_BUTTON, READY, NO_WIFI, UNREACHABLE, SENDING }
    val status: Status get() = when {
        actionPending -> Status.SENDING
        session() == null -> Status.WAITING_CARPLAY
        ready -> if (guard.idleObserved) Status.READY else Status.WAITING_BUTTON
        readPending -> Status.CHECKING
        else -> failure ?: Status.CHECKING
    }

    private fun notifyStatus() {
        val state = status.name
        if (state != lastDiagnostic) {
            lastDiagnostic = state
            android.util.Log.i(TAG, "state=$state internetRequired=false")
            // Scalars only: no address, accessory identity, key, payload or physical position.
            worker.execute {
                runCatching {
                    val file = File(app.noBackupFilesDir, "c11-gate-diagnostics.txt")
                    val old = if (file.isFile && file.length() <= 8192) file.readLines().takeLast(31) else emptyList()
                    file.writeText((old + "${System.currentTimeMillis()} state=$state").joinToString("\n", postfix = "\n"))
                }
            }
        }
        changed()
    }
    val ready: Boolean get() = !closed && !actionPending && verifiedAt >= 0 &&
        SystemClock.elapsedRealtime() - verifiedAt in 0..10_000

    private val poll = object : Runnable {
        override fun run() {
            if (closed || !visible) return
            if (session() == null) notifyStatus()
            if (!readPending && !actionPending && session() != null) {
                readPending = true
                val generation = epoch
                worker.execute {
                    val outcome = try {
                        client().verifyAvailable()
                        null
                    } catch (_: NoWifiRoute) { Status.NO_WIFI }
                    catch (_: Exception) { Status.UNREACHABLE }
                    main.post {
                        readPending = false
                        if (!closed && generation == epoch) {
                            failure = outcome
                            verifiedAt = if (outcome == null) SystemClock.elapsedRealtime() else -1
                            notifyStatus()
                        }
                    }
                }
                notifyStatus()
            }
            main.postDelayed(this, 5_000)
        }
    }

    fun setVisible(value: Boolean) {
        if (closed) return
        visible = value
        if (!value) pausedAt = SystemClock.elapsedRealtime()
        main.removeCallbacks(poll)
        if (value) main.post(poll)
        notifyStatus()
    }

    fun receive(payload: ByteArray?, receivedAt: Long) {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        if (payload == null && receivedAt == -1L) {
            epoch++
            verifiedAt = -1
            guard.reset(now)
            failure = null
            notifyStatus()
            return
        }
        // Allow the short delivery race with the OEM mirror adjustment page, but verify its focus
        // before acting if the host has already paused. Other background presses are ineligible.
        val mirrorGrace = !visible && pausedAt >= 0 && now - pausedAt in 0..350
        val token = session()
        if (!guard.accept(payload, receivedAt, now, ready && token != null && allowed() && (visible || mirrorGrace))) {
            notifyStatus()
            return
        }
        actionPending = true
        val generation = epoch
        val requiredFocusCheck = !visible
        notifyStatus()
        worker.execute {
            var accepted = false
            var attempted = false
            try {
                if (requiredFocusCheck && !mirrorFocused()) throw IOException("Gate context no longer visible")
                val client = client()
                try {
                    accepted = client.activateOnce {
                        onMain {
                            !closed && epoch == generation && session() === token && allowed() &&
                                SystemClock.elapsedRealtime() - receivedAt in 0..3_000
                        }
                    }.executionReported
                } finally { attempted = client.controlRequestAttempted }
            } catch (_: Exception) { /* No retry; a lost acknowledgement may follow a real activation. */ }
            android.util.Log.i(TAG, "buttonActivation attempted=$attempted executionReported=$accepted physicalPosition=UNKNOWN")
            main.post {
                actionPending = false
                if (!closed) {
                    verifiedAt = -1
                    failure = null
                    result(accepted)
                    notifyStatus()
                    if (visible) { main.removeCallbacks(poll); main.post(poll) }
                }
            }
            // Return only from the OEM adjustment UI reached by this press. Never steal camera,
            // SystemUpdate, launcher or passenger-display focus. This does not suppress the OEM UI.
            if (SystemClock.elapsedRealtime() - receivedAt <= 1_500 && mirrorFocused()) {
                main.post {
                    if (!closed && epoch == generation && session() === token && !visible && allowed() &&
                        SystemClock.elapsedRealtime() - receivedAt <= 1_500) restore()
                }
            }
        }
    }

    private fun onMain(check: () -> Boolean): Boolean {
        val done = CountDownLatch(1)
        var value = false
        main.post { try { value = check() } finally { done.countDown() } }
        return done.await(200, TimeUnit.MILLISECONDS) && value
    }

    private fun mirrorFocused(): Boolean = runCatching {
        LocalAdb(AdbKeys.load(app)).use { adb ->
            adb.connect(mayAsk = false) == LocalAdb.Access.READY &&
                C11GateFocusPolicy.mirrorAdjustmentsFocused(adb.shell("dumpsys window displays", 500).orEmpty(),
                    android.os.Process.myUid() / 100_000)
        }
    }.getOrDefault(false)

    private class NoWifiRoute : IOException("No local Wi-Fi route")

    private fun client(): RefossGarageClient {
        clientFactory?.let { return it() }
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val network = manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                manager.getLinkProperties(network)?.routes?.any {
                    !it.isDefaultRoute && it.destination.contains(config.address)
                } == true
        } ?: throw NoWifiRoute()
        return RefossGarageClient(config.key, config.deviceId, RefossGarageClient.Exchange { body ->
            network.socketFactory.createSocket().use { socket ->
                val deadline = deadlines.schedule({ runCatching { socket.close() } }, 2_000, TimeUnit.MILLISECONDS)
                try { RefossLocalHttp.post(socket, config.address, body) }
                finally { deadline.cancel(false) }
            }
        })
    }

    fun close() {
        closed = true
        epoch++
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "DiPlay-C11-Gate"
        private val deadlines = ScheduledThreadPoolExecutor(1, { Thread(it, "refoss-http-deadline").apply { isDaemon = true } })
            .apply { removeOnCancelPolicy = true }

        fun load(context: Context, session: () -> Any?, allowed: () -> Boolean, changed: () -> Unit,
                 result: (Boolean) -> Unit, restore: () -> Unit): C11RefossGate? {
            if (!VehicleIntegrationPolicy.isLeapmotorC11(context)) return null
            return runCatching {
                val file = File(context.noBackupFilesDir, "c11-refoss-gate.json")
                if (!file.isFile || file.length() > 4096) return null
                val json = JSONObject(file.readText())
                // The owner confirmed that the current OEM double assignment only opens adjustment UI.
                if (!json.optBoolean("enabled") || !json.optBoolean("mirrorAdjustmentOnly")) return null
                val parts = json.getString("address").split('.')
                require(parts.size == 4 && parts.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 })
                val numbers = parts.map(String::toInt)
                require(numbers[0] == 10 || numbers[0] == 172 && numbers[1] in 16..31 || numbers[0] == 192 && numbers[1] == 168)
                val key = json.getString("key")
                val id = json.getString("deviceId")
                require(key.matches(Regex("[a-fA-F0-9]{32}")) && id.matches(Regex("[a-fA-F0-9]{32}")))
                C11RefossGate(context.applicationContext,
                    Config(InetAddress.getByAddress(numbers.map(Int::toByte).toByteArray()), key, id),
                    session, allowed, changed, result, restore)
            }.getOrNull()
        }
    }
}
