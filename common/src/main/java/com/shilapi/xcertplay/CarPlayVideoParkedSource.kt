package com.shilapi.xcertplay

import java.io.Closeable

/** Cached verified gear only; construction/read/close must not wait for platform I/O. */
internal interface CarPlayVideoParkedSource : Closeable {
    fun readParked(): Boolean?
}

/** One explicitly supplied source per real AirPlay session, acquired lazily on the gate worker. */
internal class CarPlayVideoParkedSession(
    private val factory: (() -> CarPlayVideoParkedSource?)?,
    private val ordinaryRead: () -> Boolean?,
) : Closeable {
    private val lock = Any()
    private var closed = false
    private var active = false
    private var epoch = 0L
    private var attempted = false
    private var source: CarPlayVideoParkedSource? = null

    fun begin() = transition(activate = true, retire = false)
    fun end() = transition(activate = false, retire = false)
    override fun close() = transition(activate = false, retire = true)

    private fun transition(activate: Boolean, retire: Boolean) {
        val previous = synchronized(lock) {
            if (closed) return
            closed = retire
            active = activate
            epoch++
            attempted = false
            source.also { source = null }
        }
        previous?.let { runCatching { it.close() } }
    }

    fun readParked(): Boolean? {
        var acquire = false
        val generation: Long
        val selected: CarPlayVideoParkedSource?
        synchronized(lock) {
            if (closed) return null
            generation = epoch
            if (factory == null) selected = null
            else {
                if (!active) return null
                selected = source
                if (selected == null && !attempted) { attempted = true; acquire = true }
            }
        }
        if (factory == null) {
            val value = runCatching(ordinaryRead).getOrNull()
            return synchronized(lock) { value.takeIf { !closed && epoch == generation } }
        }
        var current = selected
        if (acquire) {
            val created = runCatching(factory).getOrNull()
            val retained = synchronized(lock) {
                if (!closed && active && epoch == generation) { source = created; true } else false
            }
            if (!retained) { created?.let { runCatching { it.close() } }; return null }
            current = created
        }
        val readingSource = current ?: return null
        val value = runCatching { readingSource.readParked() }.getOrNull()
        return synchronized(lock) {
            value.takeIf { !closed && active && epoch == generation && source === readingSource }
        }
    }
}
