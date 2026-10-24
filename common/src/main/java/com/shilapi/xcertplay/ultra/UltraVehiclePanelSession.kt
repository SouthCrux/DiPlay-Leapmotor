package com.shilapi.xcertplay.ultra

import android.content.Context
import android.graphics.Bitmap
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot

/** Local read-only vehicle panel. The injected provider must read its already leased fresh cache.
 * No telemetry acquisition, vehicle writes, Android Activity launch or phone state is performed.
 */
class UltraVehiclePanelSession internal constructor(
    private val provider: VehicleStatusProvider,
    private val renderer: UltraVehiclePanelRenderer,
) : AutoCloseable {
    constructor(context: Context, provider: VehicleStatusProvider) : this(provider, UltraVehiclePanelRenderer(context))

    enum class Page { SUMMARY, BATTERY_DETAILS }
    enum class Action { BATTERY_DETAILS, BACK }
    data class HitRegion(val action: Action, val left: Float, val top: Float, val right: Float, val bottom: Float) {
        internal fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    }
    /** Ownership transfers to the caller. Keep pixels alive through composition, then close them.
     * Session close retires input and in-flight rendering, never pixels already borrowed by a caller.
     */
    class Frame internal constructor(val bitmap: Bitmap, val sequence: Long, val page: Page,
        val hitRegions: List<HitRegion>, val contentDescription: String) : AutoCloseable {
        override fun close() { if (!bitmap.isRecycled) bitmap.recycle() }
    }
    internal data class Reading(val percent: Double, val rangeKm: Int) {
        companion object {
            fun from(snapshot: VehicleStatusSnapshot?): Reading? = snapshot?.takeIf {
                it.batteryPercent.isFinite() && it.batteryPercent in 0.0..100.0 && it.rangeKm in 0..0xffff
            }?.let { Reading(it.batteryPercent, it.rangeKm) }
        }
    }
    private data class Key(val reading: Reading?, val page: Page, val width: Int, val height: Int,
        val style: UltraVehiclePanelRenderer.Style)
    private val lock = Any()
    private var closed = false
    private var revision = 0L
    private var renderTicket = 0L
    private var sequence = 0L
    private var page = Page.SUMMARY
    private var committed: Key? = null
    private var latest: Frame? = null

    /** Returns a newly owned image only when displayed facts/page/style/geometry changed.
     * Null means retain the prior caller-owned image; after close, no image/input remains eligible.
     */
    fun nextFrame(width: Int = 1650, height: Int = 720): Frame? {
        require(width in 480..2048 && height in 240..1080 && width.toLong() * height <= 2_500_000)
        val request = synchronized(lock) {
            if (closed) return null
            Triple(++renderTicket, revision, page)
        }
        val reading = Reading.from(runCatching { provider.snapshot() }.getOrNull())
        val key = Key(reading, request.third, width, height, renderer.style())
        synchronized(lock) {
            if (closed || revision != request.second || renderTicket != request.first || key == committed) return null
        }
        val rendered = renderer.render(reading, request.third, width, height, key.style)
        return synchronized(lock) {
            if (closed || revision != request.second || renderTicket != request.first || key == committed) {
                rendered.bitmap.recycle()
                null
            } else {
                committed = key
                Frame(rendered.bitmap, ++sequence, request.third, java.util.Collections.unmodifiableList(rendered.hits.toList()), rendered.description)
                    .also { latest = it }
            }
        }
    }

    /** Coordinates are relative to the exact delivered image, before any compositor transform. */
    fun tap(frame: Frame, x: Float, y: Float): Boolean = synchronized(lock) {
        if (closed || latest !== frame || frame.bitmap.isRecycled || !x.isFinite() || !y.isFinite() ||
            x < 0 || y < 0 || x >= frame.bitmap.width || y >= frame.bitmap.height) return false
        val action = frame.hitRegions.firstOrNull { it.contains(x, y) }?.action ?: return false
        page = when (action) { Action.BATTERY_DETAILS -> Page.BATTERY_DETAILS; Action.BACK -> Page.SUMMARY }
        revision++
        latest = null
        true
    }

    /** A new protocol activation needs a fresh frame even when the cached facts are unchanged. */
    fun invalidate() = synchronized(lock) {
        if (closed) return
        revision++
        renderTicket++
        latest = null
        committed = null
    }

    override fun close() = synchronized(lock) {
        closed = true
        revision++
        latest = null
        committed = null
    }
}
