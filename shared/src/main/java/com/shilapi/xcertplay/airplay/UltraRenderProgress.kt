// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import java.util.concurrent.atomic.AtomicLong

/** Nonblocking, scalar-only failure evidence; never retains a frame, scene or host. */
internal class UltraRenderProgress {
    enum class Phase { WAITING, COMPOSING, DRAWING, PUBLISHING, IDLE }
    @Volatile var phase = Phase.WAITING
    val decoded = AtomicLong()
    val redraws = AtomicLong()
    val compositions = AtomicLong()
    val unavailable = AtomicLong()
    val composed = AtomicLong()
    val publications = AtomicLong()
    val stale = AtomicLong()

    fun fields(): String = "phase=$phase decoded=${decoded.get()} redraws=${redraws.get()} " +
        "compositions=${compositions.get()} unavailable=${unavailable.get()} " +
        "composed=${composed.get()} publications=${publications.get()} stale=${stale.get()}"
}
