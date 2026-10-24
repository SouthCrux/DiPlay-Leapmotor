// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.transport.VehicleStatusProvider
import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One serialized protocol owner per verified AirPlay session, with bounded ingress and egress. */
internal class VehicleStateRuntime(
    shared: ByteArray,
    private val source: VehicleStatusProvider,
    bind: InetAddress,
    peer: InetAddress,
    private val log: (String) -> Unit,
    climate: UltraClimateBackend? = null,
    radio: UltraRadioBackend? = null,
    radioElapsedNanos: () -> Long = android.os.SystemClock::elapsedRealtimeNanos,
) : Closeable {
    private val closed = AtomicBoolean()
    private val pending = AtomicInteger()
    private val protocol = VehicleStatePrototype(source, climate, radio, radioElapsedNanos, diagnostic = log)
    private val ids = mutableMapOf<VehicleStatePrototype.Lane, Long>()
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "diplay-vehicle-protocol").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val channels: VehicleStateChannels = VehicleStateChannels(shared, bind, peer,
        receive = { lane, id, bytes ->
            if (pending.incrementAndGet() > 16) { pending.decrementAndGet(); close() }
            else try {
                executor.execute {
                    try {
                        if (!closed.get() && channelsCurrent(lane, id)) {
                            val replies = protocol.handle(lane, bytes)
                            if (replies.isNotEmpty()) {
                                log("vehicle state answered=${protocol.lastCommand} lane=${lane.name}")
                                if (protocol.lastCommand == "configRequest") log(protocol.capabilityDiagnostics())
                            }
                            deliver(replies)
                        } else log("vehicle state rejected=inactive-channel lane=${lane.name}")
                    } finally { pending.decrementAndGet() }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { pending.decrementAndGet() }
        },
        ended = { lane, id -> submit { if (ids[lane] == id) resetChannels("channel ended") } },
    )

    init {
        executor.scheduleWithFixedDelay({
            if (!closed.get() && ids.isNotEmpty()) try {
                val high = VehicleStatePrototype.Lane.HIGH
                val highId = ids[high]
                if (highId == null || !channels.connected(high, highId)) {
                    if (!protocol.isSourceAvailable()) resetChannels("source unavailable")
                    return@scheduleWithFixedDelay
                }
                when (val update = protocol.poll()) {
                    VehicleStatePrototype.Poll.SourceLost -> resetChannels("source unavailable")
                    is VehicleStatePrototype.Poll.Messages -> deliver(update.messages)
                }
            } catch (_: Exception) { resetChannels("source failure") }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun channelsCurrent(lane: VehicleStatePrototype.Lane, id: Long): Boolean =
        ids[lane] == id && channels.isCurrent(lane, id)

    fun setup(uuid: String, id: Long, seed: String): VehicleStateChannels.Bound? {
        if (closed.get()) return null
        return try {
            executor.submit<VehicleStateChannels.Bound?> {
                if (closed.get()) null else {
                    val lane = VehicleStatePrototype.Lane.entries.firstOrNull { it.uuid.equals(uuid, true) }
                        ?: return@submit null
                    // A replacement discards subscriptions from both lanes; the phone registers anew.
                    if (ids.containsKey(lane)) { protocol.reset() }
                    channels.setup(uuid, id, seed)?.also {
                        ids[lane] = id
                        log("vehicle state channel opened lane=${lane.name}")
                    }
                }
            }.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) { close(); null }
    }

    fun teardown(id: Long) = submit {
        if (id in ids.values) resetChannels("stream teardown")
    }

    private fun deliver(messages: List<VehicleStatePrototype.Message>) {
        messages.forEach { message ->
            val id = ids[message.lane] ?: return@forEach
            if (!channels.send(message.lane, id, message.bytes)) resetChannels("send unavailable")
        }
    }

    private fun resetChannels(reason: String) {
        ids.clear()
        protocol.reset()
        channels.removeAll()
        log("vehicle state reset: $reason")
    }

    private fun submit(action: () -> Unit) {
        if (!closed.get()) try { executor.execute { if (!closed.get()) action() } }
        catch (_: java.util.concurrent.RejectedExecutionException) { /* session closed */ }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        channels.close()
        executor.shutdownNow()
        protocol.close()
    }
}
