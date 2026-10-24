package com.shilapi.xcertplay.airplay

/** Local provider delivery time, never an OEM measurement timestamp or proof of phone acceptance. */
data class UltraVehicleBatteryState(
    val chargePercent: Double?,
    val rangeKilometers: Double?,
    val locallyReceivedAtMillis: Long,
) {
    init {
        require(chargePercent == null || chargePercent.isFinite() && chargePercent in 0.0..100.0)
        require(rangeKilometers == null || rangeKilometers.isFinite() && rangeKilometers in 0.0..100_000.0)
        require(locallyReceivedAtMillis >= 0)
    }
}

/**
 * Session-scoped read-only input. The provider is queried on the existing protocol/frame worker,
 * at most once a second. It must already reject stale OEM data; this deadline only protects local
 * delivery. Missing, failed, future-dated and expired delivery explicitly invalidates both values.
 */
internal class UltraVehicleBatteryLifecycle(
    private val provider: () -> UltraVehicleBatteryState?,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    data class Update(val chargePercent: Double?, val rangeKilometers: Double?) {
        fun payload(): ByteArray = BplistCodec.encode(buildMap<String, Any> {
            chargePercent?.let { put("chargePercent", it) }
            rangeKilometers?.let { put("rangeKilometers", it) }
        })
    }
    private var closed = false
    private var lastPoll: Long? = null
    private var received: UltraVehicleBatteryState? = null
    private var lastPublished: Update? = null

    @Synchronized fun takePending(): Update? {
        if (closed) return null
        val now = clockMillis()
        val previousPoll = lastPoll
        if (previousPoll == null || now < previousPoll || now - previousPoll >= 1_000) {
            received = runCatching(provider).getOrNull()
            lastPoll = now
        }
        val fresh = received?.takeIf {
            now >= it.locallyReceivedAtMillis && now - it.locallyReceivedAtMillis <= 5_000
        }
        val update = Update(fresh?.chargePercent, fresh?.rangeKilometers)
        if (update == lastPublished) return null
        lastPublished = update
        return update
    }

    @Synchronized fun close() {
        closed = true
        received = null
        lastPublished = null
    }
}
