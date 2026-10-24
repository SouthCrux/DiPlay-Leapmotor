// SPDX-License-Identifier: GPL-3.0-only
// Panel declarations and UI Sync transport: f-io/LIVI 4bcd1335, info.rs/stack.rs.
package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.InetAddress

/** Diagnostic panel topology. The extra panel has no video stream or vehicle asset identity. */
internal object UltraHandshake {
    const val DATASTREAM_UUID = "07D9F906-8D64-4B54-A808-A20BCA2C51C2"
    const val PROBE_PANEL = "diplay-ultra-handshake-panel"
    const val PROBE_PLUGIN = "$PROBE_PANEL-plugin"

    fun panelIds(config: AirPlayConfig): Set<String> = config.activeThemeBinding?.let {
        listOfNotNull(it.main.id, it.cluster?.id).toSet()
    } ?: buildSet {
        add("${AirPlayInfoPlist.MAIN_UUID}-panel")
        if (config.cluster != null) add("${AirPlayInfoPlist.ALT_UUID}-panel")
        add(PROBE_PANEL)
        add(PROBE_PLUGIN)
    }

    fun panels(displays: List<Any?>, theme: UltraThemeBinding? = null): List<Map<String, Any?>> = buildList {
        fun panel(display: Map<*, *>, id: String, stream: String?, instruments: Boolean,
            plugin: Boolean = stream == null): Map<String, Any?> =
            linkedMapOf<String, Any?>(
                "uid" to id,
                "widthPixels" to display["widthPixels"],
                "heightPixels" to display["heightPixels"],
                "widthPhysical" to display["widthPhysical"],
                "heightPhysical" to display["heightPhysical"],
                "properties" to if (instruments) listOf("showsInstruments") else emptyList<String>(),
                "initialVideoStreams" to if (stream == null) emptyList() else listOf(mapOf(
                    "uuid" to stream, "originXPixels" to 0, "originYPixels" to 0, "zIndex" to 0,
                )),
            ).apply {
                if (plugin) put("displayPlugins", listOf(mapOf(
                    "uid" to if (theme == null) PROBE_PLUGIN else id,
                    "videoStreams" to if (stream == null) emptyList() else listOf(stream),
                )))
            }
        val normalized = displays.mapNotNull { it as? Map<*, *> }
        for (display in normalized) {
            val uuid = display["uuid"] as? String ?: continue
            val alternate = display["type"] == 111
            val conventionalNavigation = alternate && theme?.conventionalNavigation != null
            add(panel(display, if (theme == null) "$uuid-panel" else uuid, uuid,
                instruments = alternate && !conventionalNavigation,
                plugin = theme != null && !conventionalNavigation && (alternate || theme.composeMainDisplay)))
        }
        if (theme != null) return@buildList
        // The reference's no-theme topology keeps the plug-in off real video destinations.
        val sized = normalized.lastOrNull() ?: return@buildList
        add(panel(sized, PROBE_PANEL, null, true))
    }
}

/** Reset-only by default; an explicit development factory delegates inner protocol ownership. No renderer. */
internal class UltraHandshakeRuntime(
    shared: ByteArray,
    private val bind: InetAddress,
    private val peer: InetAddress,
    private val panels: Set<String>,
    private val log: (String) -> Unit,
    private val panelFactory: ((String) -> UiSyncPanelSession)? = null,
) : Closeable {
    private val secret = shared.copyOf().also { require(it.size == 32) }
    private data class Entry(val id: Long, val channel: VehicleStateChannel,
        val router: UiSyncPanelRouter? = null,
        var polling: java.util.concurrent.ScheduledFuture<*>? = null,
        val diagnostics: UiSyncProbeDiagnostics = UiSyncProbeDiagnostics())
    private var entry: Entry? = null
    private val protocols = mutableMapOf<String, UiSyncProtocol>()
    private val seeds = mutableSetOf<String>()
    private val ids = mutableSetOf<Long>()
    private var closed = false
    // Only an explicitly supplied protocol owner needs script ticks; the reset-only path has no timer.
    private val worker = panelFactory?.let {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "diplay-ultra-session").apply { isDaemon = true }
        }
    }
    data class Bound(val streamId: Long, val port: Int)

    @Synchronized fun setup(id: Long, seed: String): Bound? {
        if (closed || id <= 1 || id in ids || seed in seeds || seeds.size >= 16) return null
        val channel = try {
            VehicleStateChannel(secret, seed, bind, peer,
                receive = { receive(id, it) }, ended = { ended(id) })
        } catch (_: Exception) { return null }
        val previous = entry
        val observations = UiSyncOwnerDiagnostics()
        val panelIndices = panels.sorted().withIndex().associate { it.value to it.index }
        entry = Entry(id, channel, panelFactory?.let { factory ->
            UiSyncPanelRouter(panels, observer = { panel, sent, bytes ->
                observations.record(panelIndices.getValue(panel), sent, bytes)?.let(log)
            }, factory = factory)
        })
        seeds += seed; ids += id; protocols.clear()
        previous?.polling?.cancel(false)
        previous?.router?.close()
        previous?.channel?.close()
        channel.start()
        entry?.polling = worker?.scheduleWithFixedDelay({ poll(id) }, 16, 16, java.util.concurrent.TimeUnit.MILLISECONDS)
        log("ultra handshake: UI Sync channel listening; scriptOwner=${panelFactory != null} renderer=false telemetry=false")
        return Bound(id, channel.port)
    }

    @Synchronized private fun receive(id: Long, bytes: ByteArray) {
        val current = entry?.takeIf { !closed && it.id == id } ?: return
        try {
            val frame = Opack.decode(bytes) as? Opack.Dict ?: return
            val panel = (frame["displayUUID"] as? Opack.Text)?.value ?: return
            if (panel !in panels) { log("ultra handshake: unadvertised panel rejected"); return }
            current.router?.let { router ->
                // Exclusive ownership: never create a Kotlin UiSyncProtocol for this panel.
                if (router.receive(bytes).any { !current.channel.send(it) }) teardown(id)
                return
            }
            val protocol = protocols.getOrPut(panel) { UiSyncProtocol(panel) }
            val wasReady = protocol.ready
            val replies = protocol.handle(bytes)
            current.diagnostics.record(bytes, panels.sorted().indexOf(panel), wasReady, protocol.ready, replies.size)?.let(log)
            if (replies.any { !current.channel.send(it) }) { teardown(id); return }
            if (!wasReady && protocol.ready) log("ultra handshake: UI Sync reset complete response queued; renderer=false telemetry=false")
            else if (replies.isNotEmpty()) log("ultra handshake: UI Sync reset answered")
        } catch (_: Exception) { teardown(id) }
    }

    @Synchronized private fun poll(id: Long) {
        val current = entry?.takeIf { !closed && it.id == id } ?: return
        try {
            if (current.router?.poll()?.any { !current.channel.send(it) } == true) teardown(id)
        } catch (_: Exception) { teardown(id) }
    }

    @Synchronized fun teardown(id: Long) {
        val current = entry?.takeIf { it.id == id } ?: return
        entry = null; protocols.clear()
        current.polling?.cancel(false)
        current.router?.close()
        current.channel.close()
        log("ultra handshake: UI Sync channel closed")
    }

    @Synchronized private fun ended(id: Long) { teardown(id) }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        entry?.let { teardown(it.id) }
        worker?.shutdownNow()
        secret.fill(0)
    }
}
