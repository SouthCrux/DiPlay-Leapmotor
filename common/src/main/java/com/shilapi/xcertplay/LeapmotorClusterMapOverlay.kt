package com.shilapi.xcertplay

import android.content.Context
import android.content.DialogInterface
import android.content.pm.ApplicationInfo
import android.util.Log
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.widget.FrameLayout
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot

/** The C11's internal display rejects TYPE_PRESENTATION. Use a bounded public overlay context. */
@androidx.annotation.RequiresApi(28)
internal class LeapmotorClusterMapOverlay(
    private val context: Context,
    private val display: Display,
    private val onSurface: (Surface?) -> Unit,
) : ClusterMapWindow {
    override var outputSurface: Surface? = null
        private set
    override var mapVisible = true
        private set
    private var active = false
    private var guidance: ClusterTurnGuidance? = null
    private var root: FrameLayout? = null
    private var manager: WindowManager? = null
    private var card: ClusterTurnCardView? = null
    private var vehicleCard: ClusterVehicleCardView? = null
    private var vehicleSnapshot: VehicleStatusSnapshot? = null
    private val handler = Handler(Looper.getMainLooper())
    private val expireVehicleSnapshot = Runnable {
        vehicleSnapshot = null
        updateAlpha()
    }
    private val bufferSample = if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        ClusterBufferSample(handler,
            isCurrent = { expected -> root != null && windowOwner != null && mapVisible && active && outputSurface === expected },
            report = { Log.i(ClusterMapPresentation.TAG, it) }) else null
    private var windowOwner: Any? = null
    private var displayListenerRegistered = false
    private var dismissListener: DialogInterface.OnDismissListener? = null
    internal val layoutParams = WindowManager.LayoutParams(
        LeapmotorClusterDisplay.WIDTH, LeapmotorClusterDisplay.HEIGHT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        title = LeapmotorClusterDisplay.WINDOW_TITLE
        gravity = Gravity.TOP or Gravity.LEFT
        x = LeapmotorClusterDisplay.LEFT; y = LeapmotorClusterDisplay.TOP
        alpha = 0f; dimAmount = 0f
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) { if (displayId == display.displayId) dismiss() }
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == display.displayId && !LeapmotorClusterDisplay.accepts(context, display)) dismiss()
        }
    }

    override fun show() {
        if (root != null) return
        check(LeapmotorClusterDisplay.accepts(context, display)) { "Unverified C11 navigation display" }
        check(Settings.canDrawOverlays(context)) { "C11 map requires draw-over-other-apps permission" }
        val displayContext = context.createDisplayContext(display)
        val visualContext = if (Build.VERSION.SDK_INT >= 30)
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else displayContext
        val wm = visualContext.getSystemService(WindowManager::class.java)
        val view = FrameLayout(visualContext).apply { setBackgroundColor(Color.BLACK) }
        val owner = Any()
        val video = SurfaceView(visualContext)
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (windowOwner !== owner || root !== view) return
                val surface = holder.surface
                if (outputSurface === surface) return
                outputSurface = surface
                updateBufferSample()
                onSurface(surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (windowOwner !== owner || root !== view || outputSurface !== holder.surface) return
                outputSurface = null
                updateBufferSample()
                onSurface(null)
            }
        })
        view.addView(video, FrameLayout.LayoutParams(-1, -1))
        card = ClusterTurnCardView(visualContext, boundedC11Tile = true).apply { setGuidance(guidance) }
        view.addView(card, FrameLayout.LayoutParams(-1, -1))
        vehicleCard = ClusterVehicleCardView(visualContext)
        view.addView(vehicleCard, FrameLayout.LayoutParams(-1, -1))
        updateAlpha()
        root = view
        manager = wm
        windowOwner = owner
        try {
            // Establish the callback owner before Android can synchronously create its Surface.
            wm.addView(view, layoutParams)
            if (windowOwner !== owner) return
            context.getSystemService(DisplayManager::class.java)
                ?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
            displayListenerRegistered = true
        } catch (failure: RuntimeException) {
            // addView may have delivered callbacks before failing. Retire only this attempt, and
            // never release the Surface borrowed from Android's holder.
            if (windowOwner === owner) runCatching { retireWindow(notifyDismiss = false) }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun dismiss() {
        retireWindow(notifyDismiss = true)
    }

    private fun retireWindow(notifyDismiss: Boolean) {
        val view = root ?: return
        val wm = manager
        val hadSurface = outputSurface != null
        // Fence callbacks first: removeViewImmediate can synchronously destroy a Surface, and a
        // callback queued by an older window must never detach a replacement window's decoder.
        windowOwner = null
        bufferSample?.cancel()
        root = null
        manager = null
        card = null
        vehicleCard = null
        vehicleSnapshot = null
        handler.removeCallbacks(expireVehicleSnapshot)
        guidance = null
        active = false
        outputSurface = null
        if (displayListenerRegistered) {
            displayListenerRegistered = false
            context.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        }
        try {
            // Stop the decoder before Android tears down its borrowed holder Surface.
            if (hadSurface) onSurface(null)
        } finally {
            try {
                try { wm?.removeViewImmediate(view) }
                catch (_: IllegalArgumentException) { /* Android may already have removed this window. */ }
            }
            finally { if (notifyDismiss) dismissListener?.onDismiss(this) }
        }
    }
    override fun cancel() = dismiss()
    override fun showing(): Boolean = root != null
    override fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) { dismissListener = listener }
    override fun setStreamActive(active: Boolean) { this.active = active; updateAlpha() }
    override fun setMapVisible(visible: Boolean) {
        mapVisible = visible
        if (!visible) {
            vehicleSnapshot = null
            handler.removeCallbacks(expireVehicleSnapshot)
        }
        updateAlpha()
    }

    /** Caller publishes only the already leased, current telemetry cache. Never acquires a source.
     * A missed host refresh retires this publication; hidden/retired windows reject late updates.
     */
    fun setVehicleSnapshot(snapshot: VehicleStatusSnapshot?) {
        handler.removeCallbacks(expireVehicleSnapshot)
        vehicleSnapshot = snapshot.takeIf { root != null && mapVisible && ClusterVehicleCardView.valid(it) }
        if (vehicleSnapshot != null) handler.postDelayed(expireVehicleSnapshot, VEHICLE_PUBLICATION_MILLIS)
        updateAlpha()
    }
    private fun updateAlpha() {
        // iAP2 guidance is an independent semantic source: the phone may send turns without
        // an alternate-screen map. Keep the verified navigation tile useful in that case.
        // There is deliberately no synthetic map or road geometry behind the instruction.
        val fallback = vehicleSnapshot.takeIf { mapVisible && !active && guidance == null }
        vehicleCard?.setSnapshot(fallback)
        layoutParams.alpha = if (mapVisible && (active || guidance != null || fallback != null)) 1f else 0f
        updateBufferSample()
        root?.let { view ->
            try { manager?.updateViewLayout(view, layoutParams) }
            catch (_: IllegalArgumentException) { dismiss() }
        }
    }
    private fun updateBufferSample() {
        bufferSample?.update(outputSurface, root != null && windowOwner != null && mapVisible && active)
    }
    private companion object { const val VEHICLE_PUBLICATION_MILLIS = 5_000L }

    override fun setTurnCardOverlay(xPercent: Int, yPercent: Int, sizePercent: Int) { card?.setLayout(xPercent, yPercent, sizePercent) }
    override fun setTurnCardNightMode(night: Boolean) { card?.setNightMode(night) }
    override fun setTurnCardOpacity(percent: Int) { card?.setOpacity(percent) }
    override fun setTurnCardGuidance(guidance: ClusterTurnGuidance?) {
        this.guidance = guidance
        card?.setGuidance(guidance)
        updateAlpha()
    }
}
