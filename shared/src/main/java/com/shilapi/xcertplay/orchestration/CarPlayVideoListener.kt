package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.VideoInCar
import java.io.Closeable

/** iOS 27 video in car, played by the host in the car's own player (see [VideoInCar]). */
interface CarPlayVideoListener {
    /** A newly active AirPlay session, ordered before its playback callbacks. */
    fun onVideoSessionStarted() {}

    /** Whether the car is in P, or null when it cannot tell. Blocking; read once a second while CarPlay runs. */
    fun readParked(): Boolean?

    /** Video became allowed or not; when not, the player must close. Any thread. */
    fun onVideoAllowedChanged(allowed: Boolean)

    /** A playback message from the iPhone, e.g. insertPlayQueueItem, setRate, seek, stop. Any thread. */
    fun onVideoMessage(streamId: Long, message: Map<String, Any?>)

    /** The iPhone asks the car to show its video player (requestUI "videoplayback:"). Any thread. */
    fun onVideoUiRequested()

    /** The CarPlay session ended; the player must close. Any thread. */
    fun onVideoSessionEnded()
}

/**
 * Keeps [VideoInCar.allowed] in step with the car: allowed only while the gear reads P, so an unknown
 * gear (no ADB) keeps video off. Changes go to [onChanged].
 */
internal class VideoInCarGate(
    private val readParked: () -> Boolean?,
    private val onChanged: (Boolean) -> Unit,
    private val onObserved: (Boolean?) -> Unit = {},
    private val dispatchLock: Any = Any(),
) : Closeable {
    @Volatile private var closed = false
    private var observed = false
    private var lastParked: Boolean? = null
    private var epoch = 0L

    fun start() {
        Thread({
            while (!closed) {
                pollOnce()
                try {
                    Thread.sleep(POLL_MILLIS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "diplay-video-gate").apply { isDaemon = true }.start()
    }

    /** Platform/cache reads never hold the lifecycle lock. Reset invalidates their eventual result. */
    internal fun pollOnce() {
        val generation = synchronized(dispatchLock) {
            if (closed) return
            epoch
        }
        publish(runCatching(readParked).getOrNull(), generation)
    }

    internal fun update(parked: Boolean?) = publish(parked, synchronized(dispatchLock) { epoch })

    private fun publish(parked: Boolean?, generation: Long) {
        synchronized(dispatchLock) {
            if (closed || epoch != generation) return
            if (!observed || parked != lastParked) {
                observed = true
                lastParked = parked
                onObserved(parked)
            }
            if (closed || epoch != generation) return
            val allowed = parked == true
            if (allowed == VideoInCar.allowed) return
            VideoInCar.allowed = allowed
            onChanged(allowed)
        }
    }

    /** Synchronous withdrawal at a session boundary; callbacks share the controller's ordering lock. */
    fun invalidate() {
        synchronized(dispatchLock) {
            if (closed) return
            epoch++
            observed = false
            lastParked = null
            val changed = VideoInCar.allowed
            VideoInCar.allowed = false
            if (changed) onChanged(false)
        }
    }

    override fun close() = synchronized(dispatchLock) {
        epoch++
        closed = true
        VideoInCar.allowed = false
    }

    private companion object {
        const val POLL_MILLIS = 1_000L
    }
}
