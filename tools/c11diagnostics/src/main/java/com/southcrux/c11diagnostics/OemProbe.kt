package com.southcrux.c11diagnostics

import android.app.Application
import android.os.Process
import android.os.SystemClock
import com.leapmotor.someip.jni.SomeipSubInfo
import com.leapmotor.someip.jni.VSomeipSubNative
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class OemSignal(val service: Int, val instance: Int, val event: Int, val bytes: Int) {
    DISPLAYED_SOC(39168, 39169, 6000, 8),
    CHARGE_STATE(39168, 39169, 5702, 4),
    AC_CONNECTION(39168, 39169, 6207, 4),
    DC_CONNECTION(39168, 39169, 5905, 4),
    RANGE_CANDIDATE(4161, 20545, 301, 4),
}

internal fun interface OemEventReceiver {
    fun receive(service: Int, instance: Int, event: Int, payload: ByteArray?)
}

internal interface OemProbeCollector : OemEventReceiver {
    fun finish(status: String): JSONObject
}

internal interface OemEventClient {
    fun start(receiver: OemEventReceiver)
    fun stop()
}

/** Only the subscriber half of the public OEM JNI API. No publisher/control declarations. */
internal class NativeOemEventClient : OemEventClient {
    private val native = VSomeipSubNative()

    override fun start(receiver: OemEventReceiver) {
        check(Application.getProcessName() == "com.southcrux.c11diagnostics:oemprobe") { "Disposable process required" }
        check(native.init("southcrux_c11_readonly")) { "Native initialization failed" }
        OemSignal.entries.groupBy { it.service to it.instance }.forEach { (endpoint, signals) ->
            native.addInfo(SomeipSubInfo().apply {
                serviceId = endpoint.first
                instanceId = endpoint.second
                groupIds = intArrayOf(if (serviceId == 39168) 39171 else 17920)
                eventIds = signals.map { it.event }.toIntArray()
            })
        }
        native.setCallback { service, instance, event, payload -> receiver.receive(service, instance, event, payload) }
        native.start()
    }

    override fun stop() {
        check(Application.getProcessName() == "com.southcrux.c11diagnostics:oemprobe") { "Disposable process required" }
        // The installed JNI stop() aborts with a cross-thread JNIEnv DeleteGlobalRef error.
        // The report is saved before this point; process exit releases only our subscriptions.
        Process.killProcess(Process.myPid())
    }
}

/** Reception evidence only. Neither repeated callbacks nor the first callback establish origin age. */
internal class OemProbeSamples(private val elapsedNanos: () -> Long = SystemClock::elapsedRealtimeNanos) : OemProbeCollector {
    private data class Delivery(val at: Long, val raw: String?, val value: Number?, val valid: Boolean)
    private data class Field(
        var count: Int = 0,
        var first: Delivery? = null,
        var last: Delivery? = null,
        var changes: Int = 0,
        val transitions: ArrayDeque<Delivery> = ArrayDeque(),
    )
    private val fields = OemSignal.entries.associateWith { Field() }
    private var accepting = true

    @Synchronized override fun receive(service: Int, instance: Int, event: Int, payload: ByteArray?) {
        if (!accepting) return
        val signal = OemSignal.entries.singleOrNull { it.service == service && it.instance == instance && it.event == event }
            ?: return
        val exactSize = payload?.size == signal.bytes
        val value = if (exactSize) {
            val buffer = ByteBuffer.wrap(payload!!).order(ByteOrder.LITTLE_ENDIAN)
            if (signal == OemSignal.DISPLAYED_SOC) buffer.double else buffer.int
        } else null
        val valid = value != null && if (signal == OemSignal.DISPLAYED_SOC) {
            value.toDouble().isFinite() && value.toDouble() in 0.0..100.0
        } else true
        val delivery = Delivery(elapsedNanos(), payload?.takeIf { exactSize }?.joinToString("") { "%02x".format(it) },
            value.takeIf { valid }, valid)
        val field = fields.getValue(signal)
        if (field.count < Int.MAX_VALUE) field.count++
        val previous = field.last
        if (previous != null && (previous.raw != delivery.raw || previous.valid != delivery.valid)) {
            if (field.changes < Int.MAX_VALUE) field.changes++
            if (field.transitions.size == MAX_TRANSITIONS) field.transitions.removeFirst()
            field.transitions.addLast(delivery)
        }
        if (field.first == null) field.first = delivery
        field.last = delivery
    }

    @Synchronized override fun finish(status: String): JSONObject {
        accepting = false
        fun delivery(value: Delivery?): Any = value?.let {
            JSONObject().put("receivedAtElapsedNanos", it.at).put("payloadHex", it.raw ?: JSONObject.NULL)
                .put("candidateValue", it.value ?: JSONObject.NULL).put("payloadValid", it.valid)
                .put("originTimestamp", JSONObject.NULL)
        } ?: JSONObject.NULL
        return JSONObject().put("probeStatus", status).put("originFreshness", "UNVERIFIED")
            .put("initialDeliveryMayBeCached", true)
            .put("signals", JSONArray(fields.map { (signal, field) ->
                JSONObject().put("signal", signal.name).put("serviceId", signal.service)
                    .put("instanceId", signal.instance).put("eventId", signal.event)
                    .put("deliveries", field.count).put("first", delivery(field.first)).put("last", delivery(field.last))
                    .put("valueChanges", field.changes)
                    .put("recentTransitions", JSONArray(field.transitions.map(::delivery)))
            }))
    }

    private companion object { const val MAX_TRANSITIONS = 16 }
}

internal class OemProbeRunner(
    private val clientFactory: () -> OemEventClient = ::NativeOemEventClient,
    val samples: OemProbeCollector = OemProbeSamples(),
) {
    fun run(beforeCleanup: (JSONObject) -> Unit = {}, waitForWindow: () -> Unit): JSONObject {
        var client: OemEventClient? = null
        var status = "WINDOW_COMPLETE"
        try {
            client = clientFactory()
            client.start(samples)
            waitForWindow()
        } catch (_: LinkageError) {
            status = "NATIVE_LIBRARY_NOT_AVAILABLE"
        } catch (_: SecurityException) {
            status = "ACCESS_DENIED"
        } catch (_: Exception) {
            status = "NATIVE_PROBE_FAILED"
        } finally {
            // Invalidate before stop, which may deliver queued callbacks or block in OEM code.
            val snapshot = samples.finish(status)
            try { beforeCleanup(snapshot) } finally {
                try { client?.stop() } catch (_: Exception) { status = "STOP_FAILED" } catch (_: LinkageError) { status = "STOP_FAILED" }
            }
        }
        return samples.finish(status)
    }
}
