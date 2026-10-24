package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Phone route state for DiPlay's own card. No Android context, vendor workers or vehicle I/O. */
object CarPlayTurnOverlay {
    @Volatile private var listener: ((ClusterTurnGuidance?) -> Unit)? = null
    private val lock = Any()
    private var published: ClusterTurnGuidance? = null
    // Reuse the tested iAP2 parser and icon vocabulary, not the BYD service/broadcast transport.
    private val route = BydHudRouteState(
        staleRouteNs = 120_000_000_000L,
        emptyListHideNs = 8_000_000_000L,
        keepAcrossNoRoute = true,
    )

    fun onFrame(frame: Iap2Frame) {
        if (frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) return
        val change = synchronized(lock) { route.accept(frame.messageId, frame.payload) }
        if (change != BydHudRouteChange.NONE) refresh()
    }

    fun setListener(next: ((ClusterTurnGuidance?) -> Unit)?) {
        listener = next
        val current = synchronized(lock) { current().also { published = it } }
        next?.invoke(current)
    }

    /** A silent phone can leave stale guidance; called by the host's existing periodic tick. */
    fun refresh() {
        val next = synchronized(lock) {
            val current = current()
            if (current == published) return
            published = current
            current
        }
        listener?.invoke(next)
    }

    fun endNow(preserveAcrossDrop: Boolean = false) {
        synchronized(lock) {
            if (preserveAcrossDrop) route.holdAcrossDrop() else route.clear()
        }
        if (!preserveAcrossDrop) refresh()
    }

    private fun current(): ClusterTurnGuidance? = route.currentApple()?.let { apple ->
        ClusterTurnGuidance.from(BydClusterFrame.from(apple)).copy(
            arrivalEpochSeconds = apple.arrivalEpochSeconds,
            remainingSeconds = apple.remainingSeconds,
            remainingMeters = apple.remainingMeters,
        )
    }
}
