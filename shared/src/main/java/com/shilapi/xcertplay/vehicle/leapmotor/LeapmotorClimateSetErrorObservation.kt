package com.shilapi.xcertplay.vehicle.leapmotor

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A bounded negative VHAL diagnostic, never an actuator acknowledgement or temperature readback. */
class LeapmotorClimateSetErrorObservation {
    enum class Error {
        TRY_AGAIN, INVALID_ARG, PROPERTY_NOT_AVAILABLE, ACCESS_DENIED, UNKNOWN;

        companion object {
            fun fromWire(code: Int): Error = when (code) {
                1 -> TRY_AGAIN
                2 -> INVALID_ARG
                3 -> PROPERTY_NOT_AVAILABLE
                4 -> ACCESS_DENIED
                else -> UNKNOWN
            }
        }
    }
    enum class State { ERROR_OBSERVED, NO_MATCH_SEEN, INTERRUPTED }
    data class Result(val state: State, val error: Error? = null)

    private val arrived = CountDownLatch(1)
    private var area: Int? = null
    private var error: Error? = null
    private var result: Result? = null

    /** Arm only immediately before the one diagnostic setter; check-only never arms this observer. */
    @Synchronized fun begin(areaId: Int) {
        require(LeapmotorClimateSide.entries.any { it.areaId == areaId })
        check(area == null) { "A set-error observer belongs to one diagnostic attempt" }
        area = areaId
    }

    /** Exact scalar prefix of the captured CarPropertyEvent.createErrorEventWithErrorCode contract. */
    @Synchronized fun receive(eventType: Int, propertyId: Int, areaId: Int, status: Int,
                              timestampNanos: Long, errorCode: Int): Boolean {
        if (area == null || result != null || eventType != 1 ||
            propertyId != LeapmotorClimateController.TEMPERATURE_PROPERTY_ID || areaId != area ||
            status != 2 || timestampNanos != 0L) return false
        if (error == null) error = Error.fromWire(errorCode)
        arrived.countDown()
        return true
    }

    /** No matching callback is not success. Finishing retires callbacks from this diagnostic owner. */
    fun awaitResult(maxWaitMillis: Long): Result? {
        require(maxWaitMillis in 0..1_000)
        synchronized(this) {
            if (area == null) return null
            result?.let { return it }
        }
        var interrupted = false
        try { arrived.await(maxWaitMillis, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) {
            interrupted = true
            Thread.currentThread().interrupt()
        }
        return synchronized(this) {
            result ?: Result(when {
                error != null -> State.ERROR_OBSERVED
                interrupted -> State.INTERRUPTED
                else -> State.NO_MATCH_SEEN
            }, error).also { result = it }
        }
    }
}
