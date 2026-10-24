package com.shilapi.xcertplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import com.shilapi.xcertplay.vehicle.VehicleIntegrationPolicy
import org.json.JSONObject
import java.io.File
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Owner-provisioned physical identity measured for this boot, not a guess from screen size. */
internal data class CarPlayPassengerRoute(
    val fingerprint: String, val bootCount: Int, val logicalDisplayId: Int, val modeId: Int,
)

internal class CarPlayVideoRouteLease internal constructor(
    val session: Long, val launch: Long, val passenger: CarPlayPassengerRoute?,
)

/** No Android actions. A destination selection and an outstanding launch belong to one session. */
internal class CarPlayVideoRouteState {
    private var session = 0L
    private var sequence = 0L
    private var active = false
    private var passenger: CarPlayPassengerRoute? = null
    private var passengerBlocked = false
    private var lease: CarPlayVideoRouteLease? = null

    @Synchronized fun beginSession(): Long {
        session++
        active = true
        passenger = null
        passengerBlocked = false
        lease = null
        return session
    }
    @Synchronized fun currentSession(): Long = session
    @Synchronized fun isSession(value: Long): Boolean = active && value == session
    @Synchronized fun endSession(value: Long) {
        if (value != session) return
        active = false
        passenger = null
        passengerBlocked = false
        lease = null
    }
    @Synchronized fun selectPassenger(route: CarPlayPassengerRoute): Boolean {
        if (!active || lease != null) return false
        passenger = route
        passengerBlocked = false
        return true
    }
    @Synchronized fun selectDefault(): Boolean {
        if (!active || lease != null) return false
        passenger = null
        passengerBlocked = false
        return true
    }
    @Synchronized fun reserveLaunch(): CarPlayVideoRouteLease? {
        if (!active || lease != null || passengerBlocked) return null
        return CarPlayVideoRouteLease(session, ++sequence, passenger).also { lease = it }
    }
    @Synchronized fun findLease(session: Long, launch: Long): CarPlayVideoRouteLease? =
        lease?.takeIf { active && it.session == session && it.launch == launch }
    @Synchronized fun accepts(value: CarPlayVideoRouteLease): Boolean = active && lease === value
    @Synchronized fun release(value: CarPlayVideoRouteLease) { if (lease === value) lease = null }
    @Synchronized fun cancelLaunch() { lease = null }
    @Synchronized fun blockPassengerDestination() {
        passengerBlocked = true
        passenger = null
        lease = null
    }
}

/**
 * Public Display APIs on this firmware do not expose physical uniqueId. A no-backup route file is
 * therefore provisioned only after measuring dumpsys display for the current boot. This selector
 * validates that proof and the live logical display/mode; it never creates or repairs the file.
 */
internal object CarPlayPassengerDisplay {
    const val UNIQUE_ID = "local:130"
    const val ROUTE_FILE = "c11-passenger-route.json"
    const val WIDTH = 1920
    const val HEIGHT = 720
    private const val MAX_ROUTE_BYTES = 2048L

    fun provisionedRoute(context: Context): CarPlayPassengerRoute? = runCatching {
        if (Build.VERSION.SDK_INT < 30 || !VehicleIntegrationPolicy.isLeapmotorC11(context) ||
            Build.FINGERPRINT != LeapmotorClusterDisplay.FINGERPRINT) return null
        val file = File(context.noBackupFilesDir, ROUTE_FILE)
        if (file.length() !in 1..MAX_ROUTE_BYTES) return null
        val value = JSONObject(file.readText())
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (boot < 0 || value.getInt("schemaVersion") != 1 || value.getInt("bootCount") != boot ||
            value.getString("fingerprint") != Build.FINGERPRINT ||
            value.getString("physicalUniqueId") != UNIQUE_ID || value.getInt("width") != WIDTH ||
            value.getInt("height") != HEIGHT) return null
        val id = value.getInt("logicalDisplayId")
        val mode = value.getInt("modeId")
        if (id <= Display.DEFAULT_DISPLAY || mode < 0) return null
        CarPlayPassengerRoute(Build.FINGERPRINT, boot, id, mode)
    }.getOrNull()

    fun display(context: Context, route: CarPlayPassengerRoute): Display? = runCatching {
        if (provisionedRoute(context) != route) return null
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(route.logicalDisplayId)
            ?: return null
        val mode = display.mode
        display.takeIf {
            it.isValid && it.displayId != Display.DEFAULT_DISPLAY && it.rotation == 0 &&
                it.flags and Display.FLAG_PRIVATE == 0 && mode.modeId == route.modeId &&
                mode.physicalWidth == WIDTH && mode.physicalHeight == HEIGHT
        }
    }.getOrNull()

    fun find(context: Context): CarPlayPassengerRoute? = provisionedRoute(context)
        ?.takeIf { display(context, it) != null }
}

/** Observes only local display availability; it does not modify routing, video availability or P. */
internal class CarPlayVideoDestinationMonitor(
    context: Context,
    private val observedDisplayId: Int,
    private val available: () -> Boolean,
    private val onLost: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean()
    private val manager = checkNotNull(context.getSystemService(DisplayManager::class.java))
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = check()
        override fun onDisplayChanged(displayId: Int) = check()
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == observedDisplayId) lost() else check()
        }
    }

    init { manager.registerDisplayListener(listener, Handler(Looper.getMainLooper())) }

    fun check() {
        if (!closed.get() && !runCatching(available).getOrDefault(false)) lost()
    }

    private fun lost() {
        if (closed.compareAndSet(false, true)) {
            manager.unregisterDisplayListener(listener)
            onLost()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) manager.unregisterDisplayListener(listener)
    }
}
