package com.shilapi.xcertplay.homeautomation

/** No action from startup values, repeated held states, stale deliveries or a single click. */
class C11GateButtonGuard {
    private var start = Long.MAX_VALUE
    private var idleSeen = false
    val idleObserved: Boolean get() = idleSeen
    private var blocked = false
    private var lastEvent = -1L
    private var lastAction = -1L

    fun reset(now: Long, knownIdle: Boolean = false) {
        start = now; idleSeen = knownIdle; blocked = false; lastEvent = -1; lastAction = -1
    }

    fun accept(payload: ByteArray?, receivedAt: Long, now: Long, eligible: Boolean): Boolean {
        if (payload?.size != 1 || receivedAt < start || receivedAt < lastEvent ||
            receivedAt > now || now - receivedAt > 500) return false
        lastEvent = receivedAt
        when (payload[0].toInt()) {
            48 -> { idleSeen = true; blocked = false; return false }
            51 -> {
                val wasBlocked = blocked
                blocked = true
                if (!idleSeen || wasBlocked || receivedAt - start < 1_000 || !eligible ||
                    lastAction >= 0 && receivedAt - lastAction < 1_000) return false
                lastAction = receivedAt
                return true
            }
            else -> return false
        }
    }
}

object C11GateFocusPolicy {
    /** Only the centre-screen OEM adjustment UI; never camera, updater or passenger display. */
    fun mirrorAdjustmentsFocused(dump: String, userId: Int): Boolean {
        if (userId < 0) return false
        val displays = Regex("(?m)^\\s*Display: mDisplayId=(\\d+)[^\\n]*").findAll(dump).toList()
        val index = displays.indexOfFirst { it.groupValues[1] == "0" }
        if (index < 0) return false
        val start = displays[index].range.last + 1
        val end = displays.getOrNull(index + 1)?.range?.first ?: dump.length
        val block = dump.substring(start, end)
        return Regex("(?m)^\\s*mCurrentFocus=Window\\{[^}\\n]* u$userId CarControlWindow}").containsMatchIn(block) &&
            Regex("(?m)^\\s*mFocusedApp=ActivityRecord\\{[^}\\n]* u$userId com\\.leapmotor\\.carcontrol/\\.MainActivity ").containsMatchIn(block)
    }
}
