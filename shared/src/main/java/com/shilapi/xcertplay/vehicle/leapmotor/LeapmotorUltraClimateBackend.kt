package com.shilapi.xcertplay.vehicle.leapmotor

import android.os.SystemClock
import com.shilapi.xcertplay.airplay.UltraClimateBackend
import java.io.Closeable
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal interface LeapmotorClimateWorker : Closeable {
    fun execute(task: () -> Unit): Closeable
    fun schedule(delayMillis: Long, task: () -> Unit): Closeable
}

/**
 * Explicit integration seam, never installed automatically. Mapping requires verification of this
 * vehicle's driving side. refresh() is opt-in; cached getters never contact the car. Only one write
 * can be pending. Closing/canceling suppresses later success; an already dispatched OEM setter
 * cannot be undone. A submitted target is never inserted into the observed-state cache.
 */
class LeapmotorUltraClimateBackend internal constructor(
    private val controller: LeapmotorClimateController,
    verifiedZoneMapping: Map<UltraClimateBackend.Zone, LeapmotorClimateSide>,
    private val worker: LeapmotorClimateWorker,
    private val elapsedNanos: () -> Long,
    private val maxCacheAgeNanos: Long,
    private val commandTimeoutNanos: Long,
    private val pollMillis: Long,
    private val authorized: () -> Boolean = { true },
) : UltraClimateBackend, Closeable {
    constructor(
        controller: LeapmotorClimateController,
        verifiedZoneMapping: Map<UltraClimateBackend.Zone, LeapmotorClimateSide> = emptyMap(),
        authorized: () -> Boolean = { true },
    ) : this(controller, verifiedZoneMapping, DefaultWorker(), SystemClock::elapsedRealtimeNanos,
        5_000_000_000L, 5_000_000_000L, 250, authorized)

    private val mapping = verifiedZoneMapping.toMap()
    private val lock = Any()
    private var closed = false
    private var refreshing = false
    private var pending: Request? = null
    @Volatile private var cache: Cache? = null

    init {
        require(mapping.isEmpty() || (mapping.keys == UltraClimateBackend.Zone.entries.toSet() &&
            mapping.values.toSet().size == mapping.size))
        require(maxCacheAgeNanos > 0 && commandTimeoutNanos > 0 && pollMillis > 0)
    }

    private data class Cache(
        val observations: Map<UltraClimateBackend.Zone, LeapmotorClimateObservation>,
    )
    private class Request(
        val zone: UltraClimateBackend.Zone, val target: Double, val started: Long,
        val complete: (Boolean) -> Unit,
    ) {
        val done = AtomicBoolean()
        var dispatch: Closeable? = null
        var timer: Closeable? = null
        var deadline: Closeable? = null
    }

    override fun capabilities(): List<UltraClimateBackend.Capability> = fresh().map { (zone, reading) ->
        UltraClimateBackend.Capability(zone, reading.minCelsius!!, reading.maxCelsius!!,
            reading.verifiedStepCelsius!!, when (mapping.getValue(zone)) {
                LeapmotorClimateSide.LEFT -> UltraClimateBackend.Seat.FRONT_LEFT
                LeapmotorClimateSide.RIGHT -> UltraClimateBackend.Seat.FRONT_RIGHT
            })
    }

    override fun snapshot(): UltraClimateBackend.Snapshot? {
        val targets = fresh().mapValues { it.value.targetCelsius!! }
        return targets.takeIf { it.isNotEmpty() }?.let { UltraClimateBackend.Snapshot(it) }
    }

    private fun fresh(): Map<UltraClimateBackend.Zone, LeapmotorClimateObservation> {
        if (!runCatching(authorized).getOrDefault(false)) return emptyMap()
        val cached = cache ?: return emptyMap()
        val now = elapsedNanos()
        return cached.observations.filterValues { reading ->
            val timestamp = reading.timestampNanos
            reading.state == LeapmotorClimateState.AVAILABLE && reading.commandsEnabled &&
                timestamp != null && timestamp > 0 && timestamp <= now && now - timestamp <= maxCacheAgeNanos
        }
    }

    /** Opening-only synchronous probe. Caller must own an unpublished backend on a worker. */
    internal fun refreshForOpening() = updateCache()

    /** Explicit broker-owned maintenance; ordinary standalone adapters remain unpolled. */
    internal fun maintain(onAuthorizationLost: () -> Unit) {
        fun tick() {
            if (synchronized(lock) { closed }) return
            if (!runCatching(authorized).getOrDefault(false)) {
                onAuthorizationLost()
                return
            }
            refresh()
            worker.schedule(1_000) { tick() }
        }
        worker.schedule(1_000) { tick() }
    }

    /** Returns false for a closed/disabled adapter or an already queued refresh. */
    fun refresh(): Boolean {
        synchronized(lock) {
            if (closed || refreshing || mapping.isEmpty()) return false
            refreshing = true
        }
        return try {
            worker.execute {
                try { updateCache() } finally { synchronized(lock) { refreshing = false } }
            }
            true
        } catch (_: Exception) {
            synchronized(lock) { refreshing = false }
            cache = null
            false
        }
    }

    private fun updateCache() {
        val observations = mutableMapOf<UltraClimateBackend.Zone, LeapmotorClimateObservation>()
        for ((zone, side) in mapping) {
            if (synchronized(lock) { closed }) return
            observations[zone] = controller.observe(side)
        }
        val next = Cache(observations.toMap())
        synchronized(lock) { if (!closed) cache = next }
    }

    override fun requestTemperature(
        zone: UltraClimateBackend.Zone, celsius: Double, complete: (Boolean) -> Unit,
    ): Closeable {
        val request = Request(zone, celsius, elapsedNanos(), complete)
        val accepted = synchronized(lock) {
            if (closed || pending != null || !celsius.isFinite() || capabilities().none { it.zone == zone }) false
            else { pending = request; true }
        }
        if (!accepted) {
            safelyComplete(complete, false)
            return Closeable {}
        }
        try {
            val deadline = worker.schedule((commandTimeoutNanos / 1_000_000L).coerceAtLeast(1)) {
                finish(request, false)
            }
            synchronized(lock) {
                if (active(request)) request.deadline = deadline else deadline.close()
            }
            val dispatch = worker.execute {
                if (!active(request)) return@execute
                val now = elapsedNanos()
                if (now < request.started || now - request.started >= commandTimeoutNanos) {
                    finish(request, false)
                    return@execute
                }
                val submitted = controller.submitTarget(mapping.getValue(zone), celsius)
                if (submitted.state != LeapmotorClimateCommandState.SUBMITTED_UNCONFIRMED || submitted.receipt == null) {
                    updateCache()
                    finish(request, false)
                } else poll(request, submitted.receipt)
            }
            synchronized(lock) {
                if (active(request)) request.dispatch = dispatch else dispatch.close()
            }
        } catch (_: Exception) { finish(request, false) }
        return Closeable { finish(request, false) }
    }

    private fun active(request: Request) = synchronized(lock) {
        !closed && pending === request && !request.done.get()
    }

    private fun poll(request: Request, receipt: LeapmotorClimateReceipt) {
        if (!active(request)) return
        val now = elapsedNanos()
        if (now < request.started || now - request.started >= commandTimeoutNanos) {
            updateCache()
            finish(request, false)
            return
        }
        val state = controller.confirmTarget(receipt).state
        updateCache()
        if (state == LeapmotorClimateCommandState.CONFIRMED) {
            finish(request, true)
        } else if (state != LeapmotorClimateCommandState.SUBMITTED_UNCONFIRMED) {
            finish(request, false)
        } else {
            try {
                val timer = worker.schedule(pollMillis) {
                    if (active(request)) {
                        try {
                            val dispatch = worker.execute { poll(request, receipt) }
                            synchronized(lock) {
                                if (active(request)) request.dispatch = dispatch else dispatch.close()
                            }
                        } catch (_: Exception) { finish(request, false) }
                    }
                }
                synchronized(lock) {
                    if (active(request)) request.timer = timer else timer.close()
                }
            } catch (_: Exception) { finish(request, false) }
        }
    }

    private fun finish(request: Request, success: Boolean) {
        var deliveredSuccess = false
        val invoke = synchronized(lock) {
            if (!request.done.compareAndSet(false, true)) false else {
                deliveredSuccess = success && !closed && runCatching(authorized).getOrDefault(false)
                if (pending === request) pending = null
                request.dispatch?.close()
                request.dispatch = null
                request.timer?.close()
                request.timer = null
                request.deadline?.close()
                request.deadline = null
                true
            }
        }
        if (invoke) safelyComplete(request.complete, deliveredSuccess)
    }

    override fun close() {
        val current = synchronized(lock) {
            if (closed) return
            closed = true
            cache = null
            pending
        }
        current?.let { finish(it, false) }
        worker.close()
    }

    private fun safelyComplete(callback: (Boolean) -> Unit, success: Boolean) {
        runCatching { callback(success) }
    }

    private class DefaultWorker : LeapmotorClimateWorker {
        private val io = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "DiPlay-verified-climate").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        private val timers = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "DiPlay-climate-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        override fun execute(task: () -> Unit): Closeable {
            val future = io.schedule(task, 0, TimeUnit.MILLISECONDS)
            return Closeable { future.cancel(false) }
        }
        override fun schedule(delayMillis: Long, task: () -> Unit): Closeable {
            val future = timers.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
            return Closeable { future.cancel(false) }
        }
        override fun close() { timers.shutdownNow(); io.shutdownNow() }
    }
}
