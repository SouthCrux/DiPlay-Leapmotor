// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import java.io.Closeable

/** Explicitly injected, verified vehicle access. No implementation is selected automatically. */
interface UltraClimateBackend {
    enum class Zone { DRIVER, PASSENGER }
    enum class Seat { FRONT_LEFT, FRONT_RIGHT }
    data class Capability(
        val zone: Zone,
        val minCelsius: Double,
        val maxCelsius: Double,
        val stepCelsius: Double,
        val seat: Seat,
    )
    /** Only fresh readings observed from the car, never requested or inferred values. */
    data class Snapshot(val targetsCelsius: Map<Zone, Double>)
    /** Cached, nonblocking access; limits and temperature step must be verified for this car. */
    fun capabilities(): List<Capability>
    /** Cached and nonblocking; null when unavailable or stale. */
    fun snapshot(): Snapshot?
    /** Nonblocking. True means the car reported the requested setting; cancellation must be nonblocking and safe, including from completion callbacks. */
    fun requestTemperature(zone: Zone, celsius: Double, complete: (Boolean) -> Unit): Closeable
}
