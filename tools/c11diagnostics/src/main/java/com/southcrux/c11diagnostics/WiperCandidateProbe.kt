package com.southcrux.c11diagnostics

import android.app.Application
import android.os.Process
import android.os.SystemClock
import com.leapmotor.someip.jni.SomeipSubInfo
import com.leapmotor.someip.jni.VSomeipSubNative
import org.json.JSONArray
import org.json.JSONObject

/** Inventory candidates only: neither wire type nor physical meaning is inferred. */
internal enum class WiperCandidate(val event: Int) {
    FRONT_STATUS(3826), FRONT_SWITCH(3843), REAR_SWITCH(3844),
    FRONT_STOP_FEEDBACK(2603), FRONT_MOTOR_STATUS(2604),
    REAR_CONTROL_FEEDBACK(2725), REAR_STOP_FEEDBACK(2726),
    RAIN_REQUESTED_SPEED(3100), RAIN_LEVEL(3101), RAIN_FAULT(3104),
    INTERVAL_SETTING(10614), MAINTENANCE_MODE(3849),
}

internal class NativeWiperCandidateClient : OemEventClient {
    private val native = VSomeipSubNative()
    override fun start(receiver: OemEventReceiver) {
        check(Application.getProcessName() == "com.southcrux.c11diagnostics:oemprobe")
        check(native.init("southcrux_c11_readonly"))
        native.addInfo(SomeipSubInfo().apply {
            serviceId = 39168; instanceId = 39169; groupIds = intArrayOf(39171)
            eventIds = WiperCandidate.entries.map { it.event }.toIntArray()
        })
        native.setCallback { service, instance, event, payload -> receiver.receive(service, instance, event, payload) }
        native.start()
    }
    override fun stop() {
        check(Application.getProcessName() == "com.southcrux.c11diagnostics:oemprobe")
        // Same installed JNI as the existing probe: process exit avoids its broken stop ABI.
        Process.killProcess(Process.myPid())
    }
}

internal class WiperCandidateSamples(
    private val elapsedNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : OemProbeCollector {
    private data class Delivery(val at: Long, val bytes: Int?, val hex: String?)
    private class Field {
        var count = 0
        var changes = 0
        var first: Delivery? = null
        var last: Delivery? = null
        val transitions = ArrayDeque<Delivery>()
    }
    private val fields = WiperCandidate.entries.associateWith { Field() }
    private var accepting = true

    @Synchronized override fun receive(service: Int, instance: Int, event: Int, payload: ByteArray?) {
        if (!accepting || service != 39168 || instance != 39169) return
        val candidate = WiperCandidate.entries.firstOrNull { it.event == event } ?: return
        val retained = payload?.takeIf { it.size in 1..8 }
        val delivery = Delivery(elapsedNanos(), payload?.size,
            retained?.joinToString("") { "%02x".format(it.toInt() and 255) })
        val field = fields.getValue(candidate)
        if (field.count < Int.MAX_VALUE) field.count++
        field.last?.let { previous ->
            if (previous.bytes != delivery.bytes || previous.hex != delivery.hex) {
                if (field.changes < Int.MAX_VALUE) field.changes++
                if (field.transitions.size == 8) field.transitions.removeFirst()
                field.transitions.addLast(delivery)
            }
        }
        if (field.first == null) field.first = delivery
        field.last = delivery
    }

    @Synchronized override fun finish(status: String): JSONObject {
        accepting = false
        fun delivery(value: Delivery?): Any = value?.let {
            JSONObject().put("receivedAtElapsedNanos", it.at)
                .put("payloadBytes", it.bytes ?: JSONObject.NULL)
                .put("payloadHex", it.hex ?: JSONObject.NULL)
                .put("payloadRetained", it.hex != null).put("originTimestamp", JSONObject.NULL)
        } ?: JSONObject.NULL
        return JSONObject().put("probeKind", "WIPER_CANDIDATES").put("probeStatus", status)
            .put("routeVerified", false).put("wireTypeVerified", false).put("semanticsVerified", false)
            .put("originFreshness", "UNVERIFIED").put("initialDeliveryMayBeCached", true)
            .put("requestedServiceId", 39168).put("requestedInstanceId", 39169).put("requestedGroupId", 39171)
            .put("signals", JSONArray(fields.map { (candidate, field) ->
                JSONObject().put("candidate", candidate.name).put("eventId", candidate.event)
                    .put("deliveries", field.count).put("payloadChanges", field.changes)
                    .put("first", delivery(field.first)).put("last", delivery(field.last))
                    .put("recentTransitions", JSONArray(field.transitions.map(::delivery)))
            }))
    }
}
