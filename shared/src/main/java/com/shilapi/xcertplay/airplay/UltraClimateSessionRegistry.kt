// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import java.io.Closeable
import kotlin.math.abs
import kotlin.math.round

/**
 * Explicit handoff from an authorized vehicle broker to individual CarPlay sessions.
 * Empty by default: no manager acquisition, permission request, service binding or OEM discovery.
 * The broker must supply independently owned, already refreshed backends with verified physical
 * seats and temperature increments. Factory work runs on the acquiring caller, never under lock;
 * callers must use a development/session worker if opening requires I/O.
 */
class UltraClimateSessionRegistry : Closeable {
    fun interface Factory { fun open(): OwnedBackend? }
    data class OwnedBackend(val backend: UltraClimateBackend, val owner: Closeable)
    private class Entry(val factory: Factory) {
        var revoked = false
        var opening = 0
        val leases = mutableSetOf<Lease>()
    }
    class Lease internal constructor(
        rawBackend: UltraClimateBackend,
        private val owner: Closeable,
        private val retired: (Lease) -> Unit,
    ) : Closeable {
        private val gate = Any()
        private var closed = false
        private class Request(val complete: (Boolean) -> Unit) {
            var starting = true
            var immediate: Boolean? = null
            var done = false
            var cancellation: Closeable? = null
        }
        private val pending = mutableSetOf<Request>()
        /** Revocation gates all reads, dispatch and completions even if the broker's owner is inert. */
        val backend: UltraClimateBackend = object : UltraClimateBackend {
            override fun capabilities(): List<UltraClimateBackend.Capability> = synchronized(gate) {
                if (closed) emptyList() else rawBackend.capabilities()
            }
            override fun snapshot(): UltraClimateBackend.Snapshot? = synchronized(gate) {
                if (closed) null else rawBackend.snapshot()
            }
            override fun requestTemperature(zone: UltraClimateBackend.Zone, celsius: Double,
                complete: (Boolean) -> Unit): Closeable {
                val request = Request(complete)
                var outcome: Boolean? = null
                synchronized(gate) {
                    if (closed) {
                        request.done = true
                        outcome = false
                    } else {
                        pending += request
                        try {
                            // Backend contract requires cached/nonblocking dispatch. Serialize dispatch
                            // against revocation; synchronous callbacks are delivered after releasing gate.
                            request.cancellation = rawBackend.requestTemperature(zone, celsius) { success ->
                                val deferred = synchronized(gate) {
                                    if (request.starting) {
                                        if (request.immediate == null) request.immediate = success
                                        true
                                    } else false
                                }
                                if (!deferred) finish(request, success)
                            }
                        } catch (_: Exception) { request.immediate = false }
                        request.starting = false
                        if (request.immediate != null) {
                            request.done = true
                            pending.remove(request)
                            outcome = request.immediate
                        }
                    }
                }
                outcome?.let { result ->
                    runCatching { request.cancellation?.close() }
                    // A revocation between dispatch and callback still suppresses success.
                    deliver(request.complete, result)
                }
                return Closeable { finish(request, false) }
            }
        }
        private fun deliver(callback: (Boolean) -> Unit, result: Boolean) {
            val permitted = synchronized(gate) { result && !closed }
            runCatching { callback(permitted) }
        }
        private fun finish(request: Request, success: Boolean) {
            val invoke = synchronized(gate) {
                if (request.done) false else {
                    request.done = true
                    pending.remove(request)
                    true
                }
            }
            if (invoke) {
                runCatching { request.cancellation?.close() }
                deliver(request.complete, success)
            }
        }
        override fun close() {
            val requests = synchronized(gate) {
                if (closed) return
                closed = true
                pending.toList()
            }
            requests.forEach { finish(it, false) }
            try { owner.close() } finally { retired(this) }
        }
    }
    private val lock = Any()
    private var entry: Entry? = null
    private var closed = false

    /** Closing this registration revokes current sessions and prevents future acquisitions. */
    fun register(factory: Factory): Closeable {
        val registered = synchronized(lock) {
            check(!closed) { "Registry closed" }
            check(entry == null) { "An authorized broker is already registered" }
            Entry(factory).also { entry = it }
        }
        return Closeable { revoke(registered) }
    }

    /** Returns null unless the factory supplies a currently readable, serviceable climate backend. */
    fun acquire(): Lease? {
        val selected = synchronized(lock) {
            val current = entry
            if (closed || current == null || current.revoked || current.leases.size + current.opening >= 4) return null
            current.opening++
            current
        }
        var owned: OwnedBackend? = null
        var accepted = false
        try {
            owned = selected.factory.open() ?: return null
            if (!serviceable(owned.backend)) return null
            val lease = Lease(owned.backend, owned.owner) { retired ->
                synchronized(lock) { selected.leases.remove(retired) }
            }
            synchronized(lock) {
                if (closed || selected.revoked || entry !== selected) return null
                selected.leases += lease
                accepted = true
            }
            return lease
        } catch (_: Exception) {
            return null
        } finally {
            synchronized(lock) { selected.opening-- }
            if (!accepted) owned?.let { runCatching { it.owner.close() } }
        }
    }

    private fun serviceable(backend: UltraClimateBackend): Boolean {
        val capabilities = backend.capabilities()
        val readings = backend.snapshot()?.targetsCelsius ?: return false
        if (capabilities.isEmpty() || capabilities.size > 2 ||
            capabilities.map { it.zone }.distinct().size != capabilities.size ||
            capabilities.map { it.seat }.distinct().size != capabilities.size) return false
        return capabilities.all { c ->
            val value = readings[c.zone]
            c.minCelsius.isFinite() && c.maxCelsius.isFinite() && c.minCelsius < c.maxCelsius &&
                c.stepCelsius.isFinite() && c.stepCelsius > 0 && value != null && value.isFinite() &&
                value >= c.minCelsius && value <= c.maxCelsius &&
                abs((value - c.minCelsius) / c.stepCelsius - round((value - c.minCelsius) / c.stepCelsius)) < 0.000001
        }
    }

    private fun revoke(registered: Entry) {
        val leases = synchronized(lock) {
            if (registered.revoked) return
            registered.revoked = true
            if (entry === registered) entry = null
            registered.leases.toList()
        }
        leases.forEach { runCatching { it.close() } }
    }

    override fun close() {
        val registered = synchronized(lock) {
            if (closed) return
            closed = true
            entry
        }
        registered?.let(::revoke)
    }
}
