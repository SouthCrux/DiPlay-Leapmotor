package com.shilapi.xcertplay.vehicle

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.*
import com.leapmotor.someip.jni.SomeipSubInfo
import com.leapmotor.someip.jni.VSomeipSubNative
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpAmbientDecoder
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpClimateDecoder
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpTireDecoder
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpSunshadeDecoder
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpFanDecoder
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpTireFlagsDecoder
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorQnxTirePeriod
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpChargingState
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpDoorState
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpSubsystemState
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpMeasurementsState
import java.util.concurrent.Executors

/** Owns read-only JNI in a disposable process. Never loads the firmware library in CarPlay. */
class LeapmotorTelemetryService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var receiver: Messenger? = null
    private var started = false
    private val initializationTimeout = Runnable { terminate() }
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == SUBSCRIBE && message.replyTo != null && !started) {
            started = true
            receiver = message.replyTo
            main.postDelayed(initializationTimeout, 8_000L)
            worker.execute {
                try {
                    val native = VSomeipSubNative()
                    check(native.init("diplay_c11_readonly"))
                    native.addInfo(SomeipSubInfo().apply {
                        serviceId = 39168; instanceId = 39169
                        groupIds = intArrayOf(39171); eventIds = (intArrayOf(6000,
                            LeapmotorSomeIpClimateDecoder.LEFT_TARGET_EVENT,
                            LeapmotorSomeIpClimateDecoder.RIGHT_TARGET_EVENT,
                            3403, 3503, 3603, 3703, 3404, 3504, 3604, 3704,
                            3301, 3302, 3303, 3306, 3307, 3308, 3311, 3312, 3313, 3316, 3317, 3318,
                            LeapmotorSomeIpFanDecoder.LEVEL_EVENT,
                            LeapmotorSomeIpAmbientDecoder.INTERIOR_EVENT,
                            LeapmotorSomeIpAmbientDecoder.EXTERIOR_CORRECTED_EVENT,
                            LeapmotorSomeIpSunshadeDecoder.POSITION_EVENT) + LeapmotorSomeIpChargingState.eventIds() +
                            LeapmotorSomeIpDoorState.eventIds() + LeapmotorSomeIpSubsystemState.eventIds() +
                            LeapmotorSomeIpMeasurementsState.eventIds(LeapmotorSomeIpMeasurementsState.Route.CAN))
                            .distinct().toIntArray()
                    })
                    native.addInfo(SomeipSubInfo().apply {
                        serviceId = 4161; instanceId = 20545
                        groupIds = intArrayOf(17920); eventIds = intArrayOf(301, 4114) +
                            LeapmotorSomeIpMeasurementsState.eventIds(LeapmotorSomeIpMeasurementsState.Route.CLUSTER)
                    })
                    // OEM LeapCenterManager forwards these QNX strings to the tyre widget.
                    // This is a separate service from the raw CAN integer events above.
                    native.addInfo(SomeipSubInfo().apply {
                        serviceId = 29185; instanceId = 29186
                        groupIds = intArrayOf(29184); eventIds = intArrayOf(999, 1109, 1110, 1111, 1112, 1113, 1114, 1115, 1116)
                    })
                    native.setCallback { service, instance, event, payload ->
                        if (service == 29185 && instance == 29186 && event == 999) {
                            val at = SystemClock.elapsedRealtime()
                            val decoded = try {
                                payload?.takeIf { it.size <= LeapmotorQnxTirePeriod.MAX_BYTES }?.let {
                                    LeapmotorQnxTirePeriod.decode(android.util.Base64.decode(it, android.util.Base64.DEFAULT))
                                }.orEmpty()
                            } catch (_: IllegalArgumentException) { emptyList() }
                            // Only the eight tyre fields cross the process boundary.
                            decoded.forEach { field -> forward(service, instance, 29184, field.event, field.payload, at) }
                            return@setCallback
                        }
                        if (service == 39168 && instance == 39169 && (event == 6000 ||
                                event == LeapmotorSomeIpClimateDecoder.LEFT_TARGET_EVENT ||
                                event == LeapmotorSomeIpClimateDecoder.RIGHT_TARGET_EVENT ||
                                LeapmotorSomeIpTireDecoder.channel(event) != null ||
                                LeapmotorSomeIpTireFlagsDecoder.channel(event) != null ||
                                event == LeapmotorSomeIpFanDecoder.LEVEL_EVENT ||
                                LeapmotorSomeIpAmbientDecoder.channel(event) != null ||
                                event == LeapmotorSomeIpSunshadeDecoder.POSITION_EVENT ||
                                LeapmotorSomeIpChargingState.accepts(service, instance, 39171, event) ||
                                LeapmotorSomeIpDoorState.accepts(service, instance, 39171, event) ||
                                LeapmotorSomeIpSubsystemState.accepts(service, instance, 39171, event) ||
                                LeapmotorSomeIpMeasurementsState.channel(service, instance, 39171, event) != null) ||
                            service == 4161 && instance == 20545 && (event == 301 || event == 4114 ||
                                LeapmotorSomeIpMeasurementsState.channel(service, instance, 17920, event) != null) ||
                            service == 29185 && instance == 29186 && event in 1109..1116) {
                            val at = SystemClock.elapsedRealtime()
                            // Exact bounded payload only; malformed events still invalidate the field.
                            val owned = payload?.takeIf { it.size <= if (service == 29185) 32 else 8 }?.copyOf()
                            // Native has no group argument; each service has one subscription group.
                            forward(service, instance, when (service) {
                                39168 -> 39171
                                29185 -> 29184
                                else -> 17920
                            }, event, owned, at)
                        }
                    }
                    native.start()
                    main.post { main.removeCallbacks(initializationTimeout) }
                } catch (_: Exception) { main.post { terminate() } }
                catch (_: LinkageError) { main.post { terminate() } }
            }
        }
        true
    })

    override fun onBind(intent: Intent): IBinder? =
        if (Build.VERSION.SDK_INT >= 28 && VehicleIntegrationPolicy.isLeapmotorC11(this) &&
            Application.getProcessName() == "$packageName:c11telemetry") messenger.binder else null

    private fun forward(service: Int, instance: Int, group: Int, event: Int, payload: ByteArray?, at: Long) {
        main.post {
            try {
                receiver?.send(Message.obtain(null, EVENT).apply {
                    data = Bundle().apply {
                        putInt("service", service); putInt("instance", instance); putInt("group", group)
                        putInt("event", event); putByteArray("payload", payload); putLong("receivedAt", at)
                    }
                })
            } catch (_: RemoteException) { terminate() }
        }
    }

    override fun onUnbind(intent: Intent): Boolean { terminate(); return false }
    override fun onDestroy() { terminate(); super.onDestroy() }

    private fun terminate() {
        receiver = null
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        // Firmware stop() aborts with cross-thread JNIEnv usage. Exit only our dedicated process.
        if (Build.VERSION.SDK_INT >= 28 && Application.getProcessName() == "$packageName:c11telemetry") {
            Process.killProcess(Process.myPid())
        }
    }

    internal companion object { const val SUBSCRIBE = 1; const val EVENT = 2 }
}
