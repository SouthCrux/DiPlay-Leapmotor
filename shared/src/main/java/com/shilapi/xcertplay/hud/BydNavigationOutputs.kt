package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.vehicle.VehicleIntegrationPolicy
import com.shilapi.xcertplay.vehicle.VehicleNavigationOutput

/** Nonblocking boundary between phone control messages and vendor services. */
object BydNavigationOutputs : VehicleNavigationOutput {
    /** Recover a journaled interrupted output when the app opens, even before a phone reconnects. */
    fun onAppOpened(context: Context) {
        // Generic Wi-Fi recovery must still run when the OEM outputs are disabled.
        com.shilapi.xcertplay.network.WifiScanPause.restoreIfNeeded(context)
        if (!VehicleIntegrationPolicy.allowsByd(context)) return
        BydBluetoothSuspend.onAppOpened(context)
        BydOemClusterNavi.restoreIfNeeded(context)
        BydDiLink3ClusterOutput.restoreIfNeeded(context)
        if (BydStandaloneHudOutput.available(context)) start(context)
        // Read the battery early, so a reading is ready when CarPlay identifies (see batteryStatus).
        if (BydOutputSettings.batteryToIphoneActive(context)) BydBatteryStatus.start(context)
        // DiLink 3 creates its cluster map display only once the cluster has projected.
        if (BydOutputSettings.enabled(context)) BydClusterBridge.prepareProjectionDisplay(context.applicationContext)
    }
    fun setDiagnosticHold(hold: Boolean) { BydStandaloneHudOutput.syntheticHold = hold }
    @Volatile private var useStandalone = false
    private val standalone = NavigationOutputWorker("diplay-standalone-output", BydStandaloneNavigationBridge::clear)
    private val hud = NavigationOutputWorker("diplay-hud-output", BydHudBridge::clear)
    private val cluster = NavigationOutputWorker("diplay-cluster-output", BydClusterBridge::clear)
    private val fceCluster = NavigationOutputWorker("diplay-fce-cluster-output", FceClusterBridge::clear)

    /** The host reports whether its CarPlay map window is on the cluster (see [BydClusterMapPause]). */
    fun setClusterMapShown(shown: Boolean) {
        BydClusterMapPause.clusterMapShown = shown
        BydClusterBridge.setMapShown(shown)
    }

    /** The running CarPlay session: told every second whether the cluster currently shows the map. */
    override fun setClusterStreamControl(control: (Boolean) -> Unit) { BydClusterMapPause.streamControl = control }

    /** Latest ADB wheel-menu navi mode, or null when it cannot be read. */
    fun clusterNaviMode(): BydClusterNaviMode? = BydClusterMapPause.lastNaviMode

    /** Called whenever the ADB navi mode changes. Pass null to stop following. */
    fun setClusterNaviModeListener(listener: ((BydClusterNaviMode?) -> Unit)?) {
        BydClusterMapPause.onNaviMode = listener
        listener?.invoke(BydClusterMapPause.lastNaviMode)
    }

    override fun clearClusterStreamControl(control: (Boolean) -> Unit) {
        if (BydClusterMapPause.streamControl == control) BydClusterMapPause.streamControl = null
    }

    /**
     * The car's battery for the iPhone's vehicle status; starts reading it over adb. The electric
     * vehicle is declared only once a reading is there (see withVehicleStatusFrom).
     */
    fun batteryStatus(context: Context): com.shilapi.xcertplay.transport.VehicleStatusProvider =
        if (VehicleIntegrationPolicy.allowsByd(context)) BydBatteryStatus.also { it.start(context) }
        else com.shilapi.xcertplay.transport.VehicleStatusProvider { null }

    /** The car's wheel speed and gear for the iPhone's dead reckoning; read over adb while asked for. */
    fun wheelSpeed(context: Context): com.shilapi.xcertplay.transport.VehicleSpeedSource =
        BydWheelSpeedSource.attach(context)
    /** Whether the car is in P (read over adb), or null when it cannot tell. Blocking. */
    fun parked(context: Context): Boolean? = if (VehicleIntegrationPolicy.allowsByd(context))
        BydParkedState.parked(context.applicationContext) else null

    override fun start(context: Context) {
        if (!VehicleIntegrationPolicy.allowsByd(context)) return
        val app = context.applicationContext
        useStandalone = BydStandaloneHudOutput.available(app)
        if (useStandalone) standalone.start { BydStandaloneNavigationBridge.initialize(app) }
        else {
            hud.start { BydHudBridge.initialize(app) }
            cluster.start { BydClusterBridge.initialize(app) }
        }
        if (FceClusterBridge.available()) fceCluster.start { FceClusterBridge.initialize(app) }
        BydClusterMapPause.initialize(app)
        BydClusterSong.attach(app)
        BydCarPlayCall.attach(app)
    }

    override fun onFrame(frame: Iap2Frame) {
        if (frame.messageId == ClusterSongState.NOW_PLAYING_UPDATE) {
            BydClusterSong.onFrame(frame)
            return
        }
        if (frame.messageId == CarPlayCallState.CALL_STATE_UPDATE) {
            BydCarPlayCall.onFrame(frame)
            return
        }
        if (frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) return
        val owned = frame // Iap2Frame is immutable and defensively copies its payload.
        fceCluster.submit { FceClusterBridge.onFrame(owned) }
        if (useStandalone) standalone.submit { BydStandaloneNavigationBridge.onFrame(owned) }
        else {
            hud.submit { BydHudBridge.onFrame(owned) }
            cluster.submit { BydClusterBridge.onFrame(owned) }
        }
    }

    /** Live next-turn state for the dashboard overlay. Called from the iAP2 thread. */
    fun setTurnOverlayListener(listener: ((ClusterTurnGuidance?) -> Unit)?) = CarPlayTurnOverlay.setListener(listener)

    /** Called every second while the presentation owner lives, even without incoming frames. */
    fun refreshTurnOverlay() = CarPlayTurnOverlay.refresh()

    /** The dashboard song setting changed; applies at once. */
    fun clusterSongChanged(enabled: Boolean) = BydClusterSong.settingChanged(enabled)

    /** The CarPlay call setting changed; applies at once. */
    fun carPlayCallsChanged(enabled: Boolean) = BydCarPlayCall.settingChanged(enabled)

    /** A CarPlay session became active; ready the call card so calls show without the watcher's start delay. */
    override fun carPlaySessionStarted() = BydCarPlayCall.sessionStarted()

    /** The iPhone's current call, for the steering wheel's call keys. */
    fun carPlayCall(): CarPlayCallCard? = BydCarPlayCall.current()

    /** The dashboard song's "only when it changes" setting changed; applies at once. */
    fun clusterSongOnChangeChanged() = BydClusterSong.onChangeSettingChanged()

    /** A short note where the song shows on the dashboard; needs the same ADB access as the song. */
    fun dashboardNote(text: String, source: Int? = null) = BydClusterSong.note(text, source)

    /** Best effort while alive; Android does not guarantee callbacks before force-stop. */
    override fun endNow(preserveTurnOverlay: Boolean) {
        standalone.clear(); hud.clear(); cluster.clear(); fceCluster.clear(); BydClusterSong.end(); BydCarPlayCall.end()
        // Only a wireless session replacement retains the card. Explicit controller close
        // and wired disconnect still clear it immediately.
        CarPlayTurnOverlay.endNow(preserveTurnOverlay)
    }
}
