package com.shilapi.xcertplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.vehicle.VehicleIntegrationPolicy
import org.json.JSONObject
import java.io.File

/** Measured Android navigation tile of the owner's C11, not a full instrument-cluster canvas. */
internal object LeapmotorClusterDisplay {
    const val FINGERPRINT = "qti/msmnile_gvmq/msmnile_gvmq:12/SQ3A.220705.003.A1/582:user/test-keys"
    const val UNIQUE_ID = "local:131"
    const val DISPLAY_WIDTH = 1920
    const val DISPLAY_HEIGHT = 720
    const val LEFT = 1200
    const val TOP = 0
    const val WIDTH = 720
    const val HEIGHT = 720
    const val WINDOW_TITLE = "DiPlay C11 navigation"
    const val ROUTE_FILE = "c11-cluster-route.json"

    private data class Route(val displayId: Int, val modeId: Int)

    /** Blocking, read-only ADB measurement. Run off the UI thread, with an already approved key.
     * Reboots invalidate logical IDs; only a fresh physical-identity measurement renews the route.
     */
    fun refreshRoute(context: Context, shell: (String) -> String?): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 28 || !VehicleIntegrationPolicy.isLeapmotorC11(context) ||
            Build.FINGERPRINT != FINGERPRINT) return false
        if (findDisplay(context) != null) return false
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (boot < 0) return false
        val measured = parseMeasurement(shell("dumpsys display") ?: return false) ?: return false
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(measured.first)
            ?: return false
        val size = ClusterMapPresentation.sizeOf(display)
        if (!matches(Build.FINGERPRINT, UNIQUE_ID, size.x, size.y, display.rotation,
                display.isValid, display.flags and Display.FLAG_PRIVATE != 0,
                display.displayId == Display.DEFAULT_DISPLAY) || display.mode.modeId != measured.second ||
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) != boot) return false
        val value = JSONObject().put("schemaVersion", 1).put("bootCount", boot)
            .put("fingerprint", FINGERPRINT).put("physicalUniqueId", UNIQUE_ID)
            .put("logicalDisplayId", measured.first).put("modeId", measured.second)
            .put("width", DISPLAY_WIDTH).put("height", DISPLAY_HEIGHT)
        val destination = File(context.noBackupFilesDir, ROUTE_FILE)
        val temporary = File(context.noBackupFilesDir, "$ROUTE_FILE.tmp")
        try {
            temporary.writeText(value.toString())
            if (!temporary.renameTo(destination)) return false
        } finally { temporary.delete() }
        accepts(context, display)
    }.getOrDefault(false)

    /** Only logical base records tie a physical unique ID to a current logical display. */
    internal fun parseMeasurement(text: String): Pair<Int, Int>? {
        if (text.length > 2_000_000) return null
        val candidates = text.lineSequence().filter {
            it.trimStart().startsWith("mBaseDisplayInfo=DisplayInfo{") &&
                Regex("uniqueId \\\"local:131\\\"(?:,|\\s)").containsMatchIn(it)
        }.toList()
        val line = candidates.singleOrNull() ?: return null
        if (!line.contains("real 1920 x 720,") || !line.contains("rotation 0,") ||
            line.contains("FLAG_PRIVATE")) return null
        val id = Regex("displayId ([0-9]+)\\\"").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val mode = Regex("(?:,|\\s)mode ([0-9]+),").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return if (id > 0 && mode >= 0) id to mode else null
    }

    /** Provisioned by the owner's ADB session after measuring dumpsys display, never inferred. */
    private fun route(context: Context): Route? = runCatching {
        if (Build.FINGERPRINT != FINGERPRINT) return null
        val file = File(context.noBackupFilesDir, ROUTE_FILE)
        if (file.length() !in 1..2048) return null
        val value = JSONObject(file.readText())
        val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (bootCount < 0 || value.getInt("schemaVersion") != 1 || value.getInt("bootCount") != bootCount ||
            value.getString("fingerprint") != FINGERPRINT || value.getString("physicalUniqueId") != UNIQUE_ID ||
            value.getInt("width") != DISPLAY_WIDTH || value.getInt("height") != DISPLAY_HEIGHT) return null
        val id = value.getInt("logicalDisplayId")
        val modeId = value.getInt("modeId")
        if (id <= Display.DEFAULT_DISPLAY || modeId < 0) null else Route(id, modeId)
    }.getOrNull()

    fun matches(fingerprint: String, uniqueId: String, width: Int, height: Int,
                rotation: Int, valid: Boolean, privateDisplay: Boolean, defaultDisplay: Boolean): Boolean =
        fingerprint == FINGERPRINT && uniqueId == UNIQUE_ID && width == DISPLAY_WIDTH &&
            height == DISPLAY_HEIGHT && rotation == 0 && valid && !privateDisplay && !defaultDisplay

    fun accepts(context: Context, display: Display): Boolean {
        if (Build.VERSION.SDK_INT < 28 || !VehicleIntegrationPolicy.isLeapmotorC11(context)) return false
        val verified = route(context) ?: return false
        if (display.displayId != verified.displayId || display.mode.modeId != verified.modeId) return false
        val size = ClusterMapPresentation.sizeOf(display)
        return matches(Build.FINGERPRINT, UNIQUE_ID, size.x, size.y, display.rotation,
            display.isValid, display.flags and Display.FLAG_PRIVATE != 0, display.displayId == Display.DEFAULT_DISPLAY)
    }

    /** Logical IDs can change. Never fall back to the passenger panel, HDMI or a BYD display. */
    fun findDisplay(context: Context): Display? = context.getSystemService(DisplayManager::class.java)
        ?.displays?.filter { accepts(context, it) }?.singleOrNull()

    fun streamConfig(content: CarPlayClusterDisplay.Content): AirPlayDisplayConfig =
        CarPlayClusterDisplay.config(WIDTH, HEIGHT, scalePercent = 100, content = content,
            baseSafeArea = CarPlayClusterDisplay.VIRTUAL_SAFE_AREA_PERCENT).copy(
            // No physical measurement of this tile; use the protocol's generic defaults.
            widthPhysicalMm = null, heightPhysicalMm = null,
        )
}
