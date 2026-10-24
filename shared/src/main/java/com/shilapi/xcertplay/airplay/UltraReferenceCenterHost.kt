// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.media.UltraSurfaceOutput
import com.shilapi.xcertplay.media.VideoCompositionFrame
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class UltraReferenceFailure { UI_SYNC_NOT_NEGOTIATED, UI_SYNC_CHANNEL_TIMEOUT, FIRST_FRAME_TIMEOUT, UI_SYNC_CHANNEL_CLOSED, BINDING_MISMATCH, RENDERER_FAILURE }

/** Narrow application bridge for the explicitly packaged research center. Prepare off the UI thread.
 * The controller owns this object; an Activity owns only a replaceable [Attachment]. */
@RequiresApi(26)
class UltraReferenceCenterHost internal constructor(
    val themeBinding: UltraThemeBinding,
    private val build: ((Bitmap) -> Unit, () -> Unit) -> UltraReferenceConnection,
    private val closeUnused: () -> Unit,
    private val output: Output = SurfaceOutput(),
    private val startupFactory: (() -> Unit) -> UltraReferenceStartupWatchdog = { UltraReferenceStartupWatchdog(onTimeout = it) },
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    internal interface Output : Closeable {
        fun diagnosticResult(): String = "UNAVAILABLE"
        fun attach(surface: Surface)
        fun detach(surface: Surface)
        fun publish(bitmap: Bitmap): Boolean
    }
    private class SurfaceOutput : Output {
        private val delegate = UltraSurfaceOutput()
        override fun diagnosticResult(): String = delegate.lastResult.name
        override fun attach(surface: Surface) = delegate.attach(surface)
        override fun detach(surface: Surface) = delegate.detach(surface)
        override fun publish(bitmap: Bitmap) = delegate.publish(bitmap)
        override fun close() = delegate.close()
    }
    private val lock = Any()
    // Canvas submission may block. Never acquire this monitor while holding the host-state lock.
    private val outputTransactionLock = Any()
    private var attachedOutputSurface: Surface? = null
    private var outputClosed = false
    private var closed = false
    private var failed = false
    private var failure: UltraReferenceFailure? = null
    val failureReason: UltraReferenceFailure? get() = synchronized(lock) { failure }
    private var attachment: Attachment? = null
    private var epoch: Any? = null
    private var epochFailure: (() -> Unit)? = null
    private var connection: UltraReferenceConnection? = null
    private var protocolOpened = false
    private var startup: UltraReferenceStartupWatchdog? = null
    private var startupTicket: Any? = null
    private val canvasAttempts = AtomicLong()
    private val canvasAccepted = AtomicLong()
    @Volatile var submittedFrames: Long = 0
        private set

    /** Replaces the previous Activity without retaining it or letting late callbacks detach its heir. */
    fun attachHost(onFailure: () -> Unit): Attachment {
        val next: Attachment
        val report: Boolean
        val notification: (() -> Unit)?
        synchronized(lock) {
            check(!closed)
            notification = attachment?.retire()
            next = Attachment(onFailure)
            attachment = next
            connection?.updateHostVisibility(false, false)
            startup?.updateHostVisibility(false, false)
            report = failed
        }
        reconcileOutput()
        notifyOutput(notification)
        if (report) onFailure()
        return next
    }

    inner class Attachment internal constructor(private var failureCallback: (() -> Unit)?) : Closeable {
        private var surface: Surface? = null
        private var ready = false
        private var resumed = false
        private var submitted = false
        private var activation = Any()
        private var outputListener: (() -> Unit)? = null
        private fun current() = !closed && attachment === this
        /** Accepted Canvas submission for this activation, never a physical-presentation claim. */
        val isOutputReady: Boolean get() = synchronized(lock) { current() && canPublish() && submitted }
        /** A hint only: observers must read [isOutputReady] after posting to their own UI thread. */
        fun setOutputListener(listener: () -> Unit) {
            val callback = synchronized(lock) { if (current()) { outputListener = listener; listener } else null }
            notifyOutput(callback)
        }
        fun handleTouch(contacts: List<AirPlayContact>, canceled: Boolean = false): Boolean = synchronized(lock) {
            if (!current()) false else connection?.handleTouch(contacts,canceled) == true
        }
        fun updateResumed(value: Boolean) {
            val callback = synchronized(lock) {
                if (!current()) return
                val changed = if (resumed != value) invalidateOutput() else null
                resumed = value
                refresh()
                changed
            }
            notifyOutput(callback)
        }
        /** Supply actual buffer dimensions, not the size of the containing Android view. */
        fun updateSurface(next: Surface, width: Int, height: Int) {
            val callback = synchronized(lock) {
                if (!current()) return
                val changed = invalidateOutput()
                // Even a rapid replacement must invalidate an in-flight composition's activation token.
                connection?.updateHostVisibility(false, false)
                surface = next
                ready = width == themeBinding.main.width && height == themeBinding.main.height && next.isValid
                refresh()
                changed
            }
            reconcileOutput()
            notifyOutput(callback)
        }
        fun detach(previous: Surface) {
            val callback = synchronized(lock) {
                if (current() && surface === previous) {
                    val changed = invalidateOutput()
                    ready = false
                    connection?.updateHostVisibility(resumed, false)
                    startup?.updateHostVisibility(resumed, false)
                    surface = null
                    changed
                } else null
            }
            reconcileOutput()
            notifyOutput(callback)
        }
        private fun refresh() {
            connection?.updateHostVisibility(resumed, ready && !failed)
            ensureFrameDeadlineLocked()
            startup?.updateHostVisibility(resumed, ready && !failed)
            startup?.arm()
        }
        internal fun refreshFromHost() = refresh()
        internal fun canPublish() = resumed && ready && !failed
        internal fun diagnosticFields() = "resumed=$resumed surfaceReady=$ready surfaceValid=${surface?.isValid == true}"
        internal fun outputSurface(): Surface? = surface.takeIf { ready && !failed }
        internal fun publicationToken(): Any = activation
        internal fun acceptsPublication(token: Any): Boolean = activation === token && canPublish()
        internal fun failureCallback() = failureCallback
        internal fun invalidateOutput(): (() -> Unit)? {
            connection?.outputInvalidated()
            activation = Any()
            val callback = if (submitted) outputListener else null
            submitted = false
            return callback
        }
        internal fun submitted(): (() -> Unit)? {
            if (submitted) return null
            submitted = true
            return outputListener
        }
        internal fun retire(): (() -> Unit)? {
            val callback = invalidateOutput()
            ready = false; resumed = false
            surface = null; failureCallback = null; outputListener = null
            return callback
        }
        override fun close() {
            val callback = synchronized(lock) {
                if (attachment === this) {
                    val changed = retire(); attachment = null
                    connection?.updateHostVisibility(false, false)
                    startup?.updateHostVisibility(false, false)
                    changed
                } else null
            }
            reconcileOutput()
            notifyOutput(callback)
        }
    }

    /** Serialize surface ownership with an in-flight Canvas without holding host-state ownership. */
    private fun reconcileOutput() = synchronized(outputTransactionLock) { reconcileOutputLocked() }

    private fun reconcileOutputLocked() {
        val target = synchronized(lock) { if (closed) null else attachment?.outputSurface() }
        if (target !== attachedOutputSurface) {
            attachedOutputSurface?.let(output::detach)
            attachedOutputSurface = null
            if (target != null && !outputClosed) {
                output.attach(target)
                attachedOutputSurface = target
            }
        }
        if (synchronized(lock) { closed } && !outputClosed) {
            outputClosed = true
            output.close()
            attachedOutputSurface = null
        }
    }

    internal fun renderer(isConnectionOpen: () -> Boolean = { true }, onConnectionFailure: () -> Unit = {}): UltraSessionRenderer {
        val token = Any()
        var notification: (() -> Unit)? = null
        val previous = synchronized(lock) {
            check(!closed && !failed) { "Reference center host unavailable" }
            epoch = token
            epochFailure = onConnectionFailure
            protocolOpened = false
            startDeadlineLocked(token, onConnectionFailure, UltraReferenceFailure.UI_SYNC_CHANNEL_TIMEOUT)
            notification = attachment?.invalidateOutput()
            connection.also { connection = null; it?.updateHostVisibility(false, false) }
        }
        notifyOutput(notification)
        previous?.close()
        val candidate = try {
            build({ bitmap ->
                val callback = synchronized(outputTransactionLock) {
                    reconcileOutputLocked()
                    val publication = synchronized(lock) {
                        if (!closed && !failed && epoch === token && protocolOpened)
                            attachment?.takeIf { it.canPublish() && it.outputSurface() === attachedOutputSurface }
                                ?.let { it to it.publicationToken() }
                        else null
                    }
                    val accepted = if (publication != null) {
                        canvasAttempts.incrementAndGet()
                        output.publish(bitmap).also { if (it) canvasAccepted.incrementAndGet() }
                    } else false
                    if (accepted) {
                        synchronized(lock) {
                            val (owner, activation) = checkNotNull(publication)
                            // A pause/resume, surface replacement, failure or connection replacement
                            // during Canvas submission cannot establish readiness for its successor.
                            if (!closed && !failed && epoch === token && protocolOpened &&
                                attachment === owner && owner.acceptsPublication(activation)) {
                                submittedFrames++
                                stopDeadlineLocked()
                                connection?.outputSubmitted(bitmap)
                                owner.submitted()
                            } else null
                        }
                    } else null
                }
                notifyOutput(callback)
            }, { fail(token, onConnectionFailure) })
        } catch (failure: Throwable) { fail(token, onConnectionFailure); throw failure }
        val accepted = synchronized(lock) {
            if (closed || failed || epoch !== token) false else {
                connection = candidate
                candidate.requestAccessoryScene(true)
                // A new connection starts suspended; callbacks never create the scene request.
                candidate.updateHostVisibility(false, false)
                attachment?.refreshFromHost()
                startup?.arm()
                true
            }
        }
        if (!accepted) { candidate.close(); error("Reference center installation retired") }
        val retired = AtomicBoolean()
        return object : UltraSessionRenderer {
            override val videoSink get() = candidate.videoSink
            override fun panel(id: String): UiSyncPanelSession {
                check(!retired.get()) { "Reference center renderer retired" }
                val panel = candidate.panel(id)
                synchronized(lock) {
                    check(!closed && !failed && epoch === token) { "Reference center connection retired" }
                    if (!protocolOpened) {
                        protocolOpened = true
                        startDeadlineLocked(token, onConnectionFailure, UltraReferenceFailure.FIRST_FRAME_TIMEOUT)
                        attachment?.refreshFromHost()
                        startup?.arm()
                    }
                }
                return object : UiSyncPanelSession {
                    override fun receive(bytes: ByteArray) = panel.receive(bytes)
                    override fun poll() = panel.poll()
                    override fun close() {
                        if (isConnectionOpen()) fail(token, onConnectionFailure, UltraReferenceFailure.UI_SYNC_CHANNEL_CLOSED)
                        retireConnection()
                    }
                }
            }
            override fun close() = retireConnection()
            private fun retireConnection() {
                if (!retired.compareAndSet(false, true)) return
                val callback = synchronized(lock) {
                    val changed = if (epoch === token) {
                        epoch = null; epochFailure = null; connection = null; protocolOpened = false
                        stopDeadlineLocked()
                        attachment?.invalidateOutput()
                    } else null
                    candidate.updateHostVisibility(false, false)
                    changed
                }
                notifyOutput(callback)
                candidate.close()
            }
        }
    }

    // Kept separate from close: a failed trial remains failed across Activity replacement.
    internal fun rejectNegotiation() = fail(null, reason = UltraReferenceFailure.UI_SYNC_NOT_NEGOTIATED)
    internal fun rejectBinding() = fail(null, reason = UltraReferenceFailure.BINDING_MISMATCH)

    /** A successful activation retires its timer. A later usable activation needs its own Canvas. */
    private fun ensureFrameDeadlineLocked() {
        val token = epoch ?: return
        val onFailure = epochFailure ?: return
        val owner = attachment ?: return
        if (closed || failed || !protocolOpened || connection == null || startup != null ||
            !owner.canPublish() || owner.isOutputReady) return
        startDeadlineLocked(token, onFailure, UltraReferenceFailure.FIRST_FRAME_TIMEOUT)
    }

    private fun startDeadlineLocked(token: Any, onConnectionFailure: () -> Unit, reason: UltraReferenceFailure) {
        stopDeadlineLocked()
        val ticket = Any()
        startupTicket = ticket
        startup = startupFactory { fail(token, onConnectionFailure, reason, ticket) }
    }

    private fun stopDeadlineLocked() {
        startupTicket = null
        startup?.close()
        startup = null
    }

    private fun fail(token: Any?, onConnectionFailure: () -> Unit = {},
        reason: UltraReferenceFailure = UltraReferenceFailure.RENDERER_FAILURE,
        expectedStartupTicket: Any? = null) {
        val callback: (() -> Unit)?
        val outputChanged: (() -> Unit)?
        val retiring: UltraReferenceConnection?
        val diagnostic: String
        synchronized(lock) {
            if (closed || failed || (token != null && epoch !== token) ||
                (expectedStartupTicket != null && startupTicket !== expectedStartupTicket)) return
            if (expectedStartupTicket != null && attachment?.canPublish() != true) {
                startup?.deferExpiredUntilVisible()
                return
            }
            diagnostic = "ultra recovery: reason=$reason " +
                (attachment?.diagnosticFields() ?: "resumed=false surfaceReady=false surfaceValid=false") +
                " canvasAttempts=${canvasAttempts.get()} canvasAccepted=${canvasAccepted.get()} output=${output.diagnosticResult()} " +
                (connection?.diagnosticFields() ?: UltraRenderProgress().fields())
            failed = true
            failure = reason
            epochFailure = null
            stopDeadlineLocked()
            retiring = connection; connection = null
            retiring?.updateHostVisibility(false, false)
            callback = attachment?.failureCallback()
            outputChanged = attachment?.retire()
        }
        runCatching { onDiagnostic(diagnostic) }
        reconcileOutput()
        notifyOutput(outputChanged)
        retiring?.close()
        runCatching(onConnectionFailure)
        callback?.let { runCatching(it) }
    }

    /** Controller teardown only; resource deletion may perform file IO. Never releases host surfaces. */
    override fun close() {
        val outputChanged: (() -> Unit)?
        val retiring = synchronized(lock) {
            if (closed) return
            closed = true; epoch = null; epochFailure = null
            stopDeadlineLocked()
            outputChanged = attachment?.retire(); attachment = null
            connection.also { connection = null; it?.updateHostVisibility(false, false) }
        }
        reconcileOutput()
        notifyOutput(outputChanged)
        try { retiring?.close() } finally { closeUnused() }
    }

    private fun notifyOutput(callback: (() -> Unit)?) { callback?.let { runCatching(it) } }

    companion object {
        /** No assets means no host. A damaged declared package throws instead of selecting another UI. */
        fun prepare(context: Context, draw: (ByteArray, VideoCompositionFrame, VideoCompositionFrame?, (String) -> ByteArray) -> Bitmap): UltraReferenceCenterHost? =
            prepare(context, draw) { System.loadLibrary("diplay_ultra") }

        internal fun prepare(context: Context,
            draw: (ByteArray, VideoCompositionFrame, VideoCompositionFrame?, (String) -> ByteArray) -> Bitmap,
            loadLibrary: () -> Unit,
        ): UltraReferenceCenterHost? = prepareWithDrawer(context, { load ->
            object : UltraReferenceFrameDrawer {
                override fun draw(bytes: ByteArray, offered: VideoCompositionFrame, current: VideoCompositionFrame?): Bitmap =
                    draw(bytes, offered, current, load)
                override fun close() = Unit
            }
        }, loadLibrary)

        fun prepareWithDrawer(context: Context,
            createDrawer: ((String) -> ByteArray) -> UltraReferenceFrameDrawer,
        ): UltraReferenceCenterHost? = prepareWithDrawer(context, createDrawer,
            loadLibrary = { System.loadLibrary("diplay_ultra") })

        /** Cached verified values only; the controller must keep its telemetry source lease alive. */
        fun prepareWithVehicleData(context: Context, vehicleSource: VehicleStatusProvider,
            createDrawer: ((String) -> ByteArray) -> UltraReferenceFrameDrawer,
        ): UltraReferenceCenterHost? = prepareWithDrawer(context, createDrawer,
            loadLibrary = { System.loadLibrary("diplay_ultra") }, vehicleSource = vehicleSource)

        internal fun prepareWithDrawer(context: Context,
            createDrawer: ((String) -> ByteArray) -> UltraReferenceFrameDrawer,
            loadLibrary: () -> Unit,
            vehicleSource: VehicleStatusProvider? = null,
        ): UltraReferenceCenterHost? {
            val app = context.applicationContext
            val first = UltraPackagedResourceSource.prepare(app) ?: return null
            var pending: UltraReferenceResourceSelection? = first
            val resourcesLock = Any()
            return UltraReferenceCenterHost(first.themeBinding, { publish, failure ->
                val selection = synchronized(resourcesLock) { pending.also { pending = null } }
                    ?: checkNotNull(UltraPackagedResourceSource.prepare(app))
                selection.use {
                    val drawer = createDrawer(it::readPng)
                    // Both a failed constructor and the constructed session can own retirement.
                    // Fence the provider so initialization failures still close it exactly once.
                    val localSettings = drawer.localSettingsFrames?.let { delegate ->
                        object : UltraLocalSettingsFrames {
                            private val closed = AtomicBoolean()
                            override fun nextFrame(phoneSenderNanos: Long): UltraLocalSettingsFrame? =
                                if (closed.get()) null else delegate.nextFrame(phoneSenderNanos)
                            override fun invalidate() { if (!closed.get()) delegate.invalidate() }
                            override fun tap(frameToken: Long, x: Float, y: Float): Boolean =
                                !closed.get() && delegate.tap(frameToken, x, y)
                            override fun close() { if (closed.compareAndSet(false, true)) delegate.close() }
                        }
                    }
                    try {
                        val runtime = if (localSettings != null) it.openRuntimeWithSettings(loadLibrary)
                            else it.openRuntime(loadLibrary)
                        try {
                            UltraVideoPipeline(it.themeBinding.main.id, "iPhoneVideo", 1920, 720, runtime,
                                drawer::draw, publish, failure, UltraSceneInputMode.REFERENCE_CENTER_ACCESSORY,
                                retireDrawing = drawer::close,
                                batteryProvider = vehicleSource?.let { source ->
                                    { source.snapshot()?.let { snapshot ->
                                        UltraVehicleBatteryState(snapshot.batteryPercent, snapshot.rangeKm.toDouble(),
                                            System.nanoTime() / 1_000_000)
                                    } }
                                }, localSettings = localSettings,
                                drawWithSettings = drawer::drawWithSettings, interaction = drawer)
                        } catch (error: Throwable) { runtime.close(); throw error }
                    } catch (error: Throwable) {
                        runCatching { localSettings?.close() }.exceptionOrNull()?.let(error::addSuppressed)
                        runCatching(drawer::close).exceptionOrNull()?.let(error::addSuppressed)
                        throw error
                    }
                }
            }, { synchronized(resourcesLock) { pending?.close(); pending = null } },
                onDiagnostic = { android.util.Log.w("xcertplay-usb", it) })
        }
    }
}

/** Internal seam keeps decoder/native details out of the Activity and makes ownership testable. */
internal interface UltraReferenceConnection : UltraSessionRenderer {
    /** Must read only atomics/volatile fields: the failing worker may hold its own locks. */
    fun diagnosticFields(): String = UltraRenderProgress().fields()
    fun updateHostVisibility(resumed: Boolean, surfaceReady: Boolean)
    fun requestAccessoryScene(request: Boolean)
    fun outputSubmitted(bitmap: Bitmap) {}
    fun outputInvalidated() {}
    fun handleTouch(contacts: List<AirPlayContact>, canceled: Boolean = false): Boolean = false
}
