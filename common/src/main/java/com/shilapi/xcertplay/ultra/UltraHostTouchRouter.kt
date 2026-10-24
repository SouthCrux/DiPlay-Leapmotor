package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.AirPlayContact

/** Activity-owned gesture ownership survives connection retirement and fallback. */
internal class UltraHostTouchRouter {
    private enum class Owner { NONE, PHONE, LOCAL }
    private var owner = Owner.NONE

    fun dispatch(contacts: List<AirPlayContact>, startsGesture: Boolean, canceled: Boolean,
        routeLocal: (List<AirPlayContact>, Boolean) -> Boolean): Boolean {
        if (startsGesture) {
            // Android began a new gesture: cancel any orphan left by a window interruption.
            routeLocal(emptyList(),true)
            owner = Owner.NONE
        }
        val consumed = when (owner) {
            Owner.NONE -> if (!canceled && contacts.any { it.down }) {
                routeLocal(contacts,false).also { owner=if (it) Owner.LOCAL else Owner.PHONE }
            } else false
            Owner.PHONE -> false
            Owner.LOCAL -> { routeLocal(contacts,canceled); true }
        }
        if (canceled || contacts.none { it.down }) owner = Owner.NONE
        return consumed
    }
}
