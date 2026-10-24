package com.shilapi.xcertplay.ultra

import android.content.Context
import android.graphics.Bitmap
import com.shilapi.xcertplay.airplay.UltraLocalSettingsFrame
import com.shilapi.xcertplay.airplay.UltraLocalSettingsFrames
import com.shilapi.xcertplay.airplay.UltraLocalSettingsInput
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import java.util.concurrent.atomic.AtomicBoolean

/** Adapts real cached vehicle readings into independently owned Settings frames. No source lease
 * or vehicle command is acquired here. The connection owns this producer and every returned frame.
 */
class UltraVehiclePanelInput internal constructor(private val panel: UltraVehiclePanelSession) : UltraLocalSettingsFrames {
    constructor(context: Context, provider: VehicleStatusProvider) : this(UltraVehiclePanelSession(context, provider))

    private class Lease(val image: UltraVehiclePanelSession.Frame, override val senderNanos: Long) : UltraLocalSettingsFrame {
        override val token: Long get() = image.sequence
        override val bitmap: Bitmap get() = image.bitmap
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) image.close() }
    }

    private val lock = Any()
    private var closed = false
    private var generation = 0L
    private var lastSenderNanos = 0L
    private var latest: Lease? = null

    override fun nextFrame(phoneSenderNanos: Long): UltraLocalSettingsFrame? {
        require(phoneSenderNanos >= 0)
        val epoch = synchronized(lock) { if (closed) return null else generation }
        val image = panel.nextFrame(UltraLocalSettingsInput.WIDTH, UltraLocalSettingsInput.HEIGHT) ?: return null
        return synchronized(lock) {
            if (closed || epoch != generation) {
                image.close()
                null
            } else {
                lastSenderNanos = maxOf(lastSenderNanos, phoneSenderNanos)
                Lease(image, lastSenderNanos).also { latest = it }
            }
        }
    }

    /** Image-local coordinates. A caller must additionally prove this token is actually presented
     * before routing a physical gesture here; creation/drawing alone does not authorize routing.
     */
    override fun tap(frameToken: Long, x: Float, y: Float): Boolean {
        val lease = synchronized(lock) { latest?.takeIf { !closed && it.token == frameToken } } ?: return false
        return panel.tap(lease.image, x, y)
    }

    override fun invalidate() {
        synchronized(lock) { if (closed) return; generation++; latest = null }
        panel.invalidate()
    }

    override fun close() {
        synchronized(lock) { if (closed) return; closed = true; generation++; latest = null }
        // Pixels already transferred stay alive until the frame coordinator closes their leases.
        panel.close()
    }
}
