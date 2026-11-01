package com.shilapi.xcertplay

/** Re-measures a missing boot-specific route while the host is visible. Never guesses a display ID. */
internal class C11ClusterRouteRecovery(
    private val eligible: () -> Boolean,
    private val hasVerifiedRoute: () -> Boolean,
    private val renew: (complete: () -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val cancel: () -> Unit,
    private val restored: () -> Unit,
) {
    private var active = false
    private var inFlight = false
    private var delay = INITIAL_DELAY

    fun start() {
        active = true
        request()
    }

    fun stop() {
        active = false
        cancel()
    }

    fun request() {
        cancel()
        if (!active || !eligible() || inFlight) return
        if (hasVerifiedRoute()) { delay = INITIAL_DELAY; return }
        inFlight = true
        renew {
            inFlight = false
            if (active && eligible()) {
                if (hasVerifiedRoute()) {
                    delay = INITIAL_DELAY
                    restored()
                } else {
                    val wait = delay
                    delay = (delay * 2).coerceAtMost(MAX_DELAY)
                    schedule(wait, ::request)
                }
            }
        }
    }

    companion object {
        const val INITIAL_DELAY = 1_000L
        const val MAX_DELAY = 30_000L
    }
}
