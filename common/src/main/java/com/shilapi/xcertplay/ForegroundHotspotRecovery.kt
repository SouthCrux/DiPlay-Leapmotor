package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.vehicle.VehicleIntegrationPolicy

/** Recover the saved C11 hotspot while a DiPlay screen or foreground connection service is active. */
internal class ForegroundHotspotRecovery(
    private val eligible: () -> Boolean,
    private val state: () -> Boolean?,
    private val enable: (() -> Boolean) -> CarHotspotTethering.Result,
    private val execute: (() -> Unit) -> Unit = { Thread(it, "diplay-hotspot-recovery").start() },
) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0
    private var visible = false
    private var pending = false
    private var retryAfter = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            if (!pending && eligible() && SystemClock.elapsedRealtime() >= retryAfter && state() == false) {
                pending = true
                val request = generation
                execute {
                    val cancelled = { generation != request || !eligible() }
                    val result = if (cancelled()) CarHotspotTethering.Result.CANCELLED
                        else runCatching { enable(cancelled) }.getOrDefault(CarHotspotTethering.Result.FAILED)
                    handler.post {
                        pending = false
                        if (generation == request) {
                            retryAfter = SystemClock.elapsedRealtime() +
                                if (result == CarHotspotTethering.Result.READY) POLL_MILLIS else FAILURE_BACKOFF_MILLIS
                        }
                    }
                }
            }
            handler.postDelayed(this, POLL_MILLIS)
        }
    }

    fun start() {
        if (visible) return
        visible = true
        generation++
        // The ordinary launch path gets the first attempt; recovery starts afterwards.
        handler.postDelayed(tick, POLL_MILLIS)
    }

    fun stop() {
        visible = false
        generation++
        handler.removeCallbacks(tick)
    }

    companion object {
        private const val POLL_MILLIS = 5_000L
        private const val FAILURE_BACKOFF_MILLIS = 60_000L

        fun forC11(context: Context): ForegroundHotspotRecovery {
            val app = context.applicationContext
            return ForegroundHotspotRecovery(
                eligible = {
                    VehicleIntegrationPolicy.isLeapmotorC11(app) &&
                        CarHotspotSettings.shouldEnable(app, AirPlayPersistence.loadWirelessEnabled(app),
                            AirPlayPersistence.loadWirelessHotspotMode(app))
                },
                state = { CarHotspotStatus.isEnabled(app) },
                enable = { cancelled ->
                    Log.i("DiPlay-Hotspot", "foreground recovery: saved hotspot is off; requesting startup")
                    CarHotspotTethering.enable(app, isCancelled = cancelled,
                        log = { Log.i("DiPlay-Hotspot", it) })
                },
            )
        }
    }
}
