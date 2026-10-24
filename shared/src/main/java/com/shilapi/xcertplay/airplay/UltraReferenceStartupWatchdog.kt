// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Counts only foreground time with a usable output for one bounded startup phase.
 * The host uses separate instances for channel opening and the first accepted Canvas submission.
 */
internal class UltraReferenceStartupWatchdog(
    timeoutMillis: Long = 10_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val schedule: (Long, () -> Unit) -> Closeable = SharedTimer::schedule,
    private val onTimeout: () -> Unit,
) : Closeable {
    private val lock = Any()
    private var remaining = timeoutMillis.also { require(it > 0) }
    private var armed = false
    private var available = false
    private var terminal = false
    private var expired = false
    private var startedAt = 0L
    private var generation = 0L
    private var task: Closeable? = null
    private data class Plan(val generation: Long, val delay: Long)

    /** Idempotent: repeated calls never replenish the visibility budget. */
    fun arm() {
        val plan = synchronized(lock) {
            if (terminal || armed) return
            armed = true
            if (available) planLocked() else null
        }
        plan?.let(::install)
    }

    fun updateHostVisibility(resumed: Boolean, surfaceReady: Boolean) {
        val cancel: Closeable?
        val plan: Plan?
        synchronized(lock) {
            val next = resumed && surfaceReady
            if (terminal || available == next) return
            cancel = task
            task = null
            if (armed && available) consumeLocked()
            available = next
            generation++
            plan = if (armed && available) planLocked() else null
        }
        cancel?.close()
        plan?.let(::install)
    }

    private fun consumeLocked() {
        val elapsed = (clock() - startedAt).coerceAtLeast(0).coerceAtMost(remaining)
        remaining -= elapsed
    }

    private fun planLocked(): Plan {
        startedAt = clock()
        return Plan(++generation, remaining)
    }

    private fun install(plan: Plan) {
        // A scheduler may run its callback before returning its cancellation handle. Publishing the
        // handle afterward must not revive a timed-out/canceled generation.
        val handle = schedule(plan.delay) { deadline(plan.generation) }
        val accepted = synchronized(lock) {
            if (!terminal && armed && available && generation == plan.generation) {
                task = handle
                true
            } else false
        }
        if (!accepted) handle.close()
    }

    private fun deadline(ticket: Long) {
        val fire: Boolean
        val next: Plan?
        synchronized(lock) {
            if (terminal || !armed || !available || generation != ticket) return
            task = null
            consumeLocked()
            fire = remaining == 0L
            if (fire) {
                terminal = true
                expired = true
                generation++
                next = null
            } else next = planLocked() // Tolerate an early scheduler callback without shortening time.
        }
        next?.let(::install)
        if (fire) onTimeout()
    }

    /** A dispatched timeout may reach the host after it paused. Keep its spent budget, and
     * retry only on the next usable activation. A successful/closed phase cannot be revived.
     */
    fun deferExpiredUntilVisible() = synchronized(lock) {
        if (!expired) return@synchronized
        expired = false
        terminal = false
        available = false
        generation++
    }

    /** Success is terminal even if it arrives before arm, such as during renderer construction. */
    fun channelOpened() = stop()

    override fun close() = stop()

    private fun stop() {
        val cancel = synchronized(lock) {
            expired = false
            if (terminal) return
            terminal = true
            generation++
            task.also { task = null }
        }
        cancel?.close()
    }

    private object SharedTimer {
        private val executor by lazy {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "diplay-ultra-startup").apply { isDaemon = true }
            }
        }
        fun schedule(delay: Long, action: () -> Unit): Closeable {
            val future = executor.schedule(action, delay, TimeUnit.MILLISECONDS)
            return Closeable { future.cancel(false) }
        }
    }
}
