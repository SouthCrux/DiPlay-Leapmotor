// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.InetAddress

/**
 * Session-scoped ownership of the two research channels. iAP and video never enter this registry.
 * Channel events carry their stream identity so the serialized session loop can discard events
 * queued before replacement. This object does not advertise capabilities or install a protocol host.
 */
internal class VehicleStateChannels(
    sharedSecret: ByteArray,
    private val bindAddress: InetAddress,
    private val peerAddress: InetAddress,
    private val receive: (VehicleStatePrototype.Lane, Long, ByteArray) -> Unit,
    private val ended: (VehicleStatePrototype.Lane, Long) -> Unit,
) : Closeable {
    private val secret = sharedSecret.copyOf().also { require(it.size == 32) }
    private data class Entry(val id: Long, val channel: VehicleStateChannel)
    private val channels = mutableMapOf<VehicleStatePrototype.Lane, Entry>()
    // Each SETUP needs a fresh seed, including replacements: resetting counters with reused keys is unsafe.
    private val seeds = mutableSetOf<String>()
    private val streamIds = mutableSetOf<Long>()
    private var closed = false

    data class Bound(val streamId: Long, val port: Int)

    @Synchronized fun setup(uuid: String, streamId: Long, seed: String): Bound? {
        check(!closed)
        val lane = VehicleStatePrototype.Lane.entries.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }
            ?: return null
        require(streamId > 1 && streamId !in streamIds) // stream 1 belongs to the existing iAP tunnel
        require(channels.none { (other, entry) -> other != lane && entry.id == streamId })
        require(seed !in seeds && seeds.size < 64)
        val channel = VehicleStateChannel(secret, seed, bindAddress, peerAddress,
            receive = { bytes ->
                if (isCurrent(lane, streamId)) receive(lane, streamId, bytes)
            },
            ended = { onEnded(lane, streamId) },
        )
        seeds += seed
        streamIds += streamId
        val old = channels.put(lane, Entry(streamId, channel))
        old?.channel?.close()
        channel.start()
        return Bound(streamId, channel.port)
    }

    @Synchronized fun isCurrent(lane: VehicleStatePrototype.Lane, streamId: Long): Boolean =
        !closed && channels[lane]?.id == streamId

    @Synchronized fun connected(lane: VehicleStatePrototype.Lane, streamId: Long): Boolean =
        !closed && channels[lane]?.let { it.id == streamId && it.channel.connected } == true

    /** Exact stream teardown only; unknown IDs and the iAP stream have no effect. */
    @Synchronized fun remove(streamId: Long) {
        val lane = channels.entries.firstOrNull { it.value.id == streamId }?.key ?: return
        channels.remove(lane)?.channel?.close()
    }

    @Synchronized fun removeAll() {
        val previous = channels.values.toList()
        channels.clear()
        previous.forEach { it.channel.close() }
    }

    fun send(lane: VehicleStatePrototype.Lane, streamId: Long, bytes: ByteArray): Boolean {
        val channel = synchronized(this) { channels[lane]?.takeIf { !closed && it.id == streamId }?.channel }
        return channel?.send(bytes) ?: false
    }

    private fun onEnded(lane: VehicleStatePrototype.Lane, streamId: Long) {
        val current = synchronized(this) {
            if (!isCurrent(lane, streamId)) false else { channels.remove(lane); true }
        }
        if (current) ended(lane, streamId)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        val previous = channels.values.toList()
        channels.clear()
        secret.fill(0)
        previous.forEach { it.channel.close() }
    }
}
