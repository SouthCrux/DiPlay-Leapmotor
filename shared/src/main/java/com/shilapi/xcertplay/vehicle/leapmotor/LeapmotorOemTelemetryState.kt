package com.shilapi.xcertplay.vehicle.leapmotor

import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stateful OEM notifications: accept a delivered value as current, including the initial replay,
 * as agreed for this C11. Unchanged SOC may be silent while range notifications keep arriving.
 * Retain it only for this transport session; 60 seconds without either event invalidates both.
 * Reception time is never presented as an OEM measurement timestamp.
 */
class LeapmotorOemTelemetryState(
    private val elapsedMillis: () -> Long,
    private val silenceMillis: Long = 60_000L,
) : VehicleStatusProvider {
    init { require(silenceMillis > 0) }
    private var soc: Double? = null
    private var range: Int? = null
    private var lastReceived: Long? = null

    @Synchronized fun receive(service: Int, instance: Int, event: Int, payload: ByteArray?, receivedAt: Long) {
        val socEvent = service == 39168 && instance == 39169 && event == 6000
        val rangeEvent = service == 4161 && instance == 20545 && event == 301
        if (!socEvent && !rangeEvent) return
        val now = elapsedMillis()
        val previous = lastReceived
        if (receivedAt < 0 || receivedAt > now || now - receivedAt > silenceMillis ||
            (previous != null && receivedAt < previous)) {
            clear()
            return
        }
        // A new callback after prolonged silence begins a new state, rather than reviving old SOC.
        if (previous != null && receivedAt - previous > silenceMillis) clear()
        val buffer = payload?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
        if (socEvent) {
            soc = if (payload?.size == 8) buffer!!.double.takeIf { it.isFinite() && it in 0.0..100.0 } else null
        } else {
            range = if (payload?.size == 4) buffer!!.int.takeIf { it in 0..0xffff } else null
        }
        lastReceived = receivedAt
    }

    @Synchronized fun clear() { soc = null; range = null; lastReceived = null }

    @Synchronized override fun snapshot(): VehicleStatusSnapshot? {
        val at = lastReceived ?: return null
        val now = elapsedMillis()
        if (now < at || now - at > silenceMillis) { clear(); return null }
        return VehicleStatusSnapshot(
            rangeKm = range ?: return null, batteryPercent = soc ?: return null,
            rangeWarning = null, maxRangeKm = null, charging = null,
            currentChargeWh = null, maxChargeWh = null,
        )
    }
}
