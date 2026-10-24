package com.shilapi.xcertplay.vehicle

import android.content.*
import android.os.*
import com.shilapi.xcertplay.transport.ManagedVehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorOemTelemetryState
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpAmbientState
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpAmbientDecoder
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpClimateState
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpTireDecoder
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpTireState
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpSunshadeState
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpFanState
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpTireFlagsDecoder
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpTireFlagsState
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorQnxTireState
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpChargingState
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpDoorState
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpSubsystemState
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpMeasurementsState
import java.util.Collections
import java.util.IdentityHashMap

/** App-process cache; Activity warmup and each controller hold separate source leases. */
object LeapmotorTelemetry {
    private val main = Handler(Looper.getMainLooper())
    private val owners = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private val state = LeapmotorOemTelemetryState(SystemClock::elapsedRealtime)
    private val charging = LeapmotorSomeIpChargingState(SystemClock::elapsedRealtime)
    private val chargingLog = DiagnosticBatch(main)
    private val doors = LeapmotorSomeIpDoorState(SystemClock::elapsedRealtime)
    private val doorLog = DiagnosticBatch(main)
    private val subsystems = LeapmotorSomeIpSubsystemState(SystemClock::elapsedRealtime)
    private val subsystemLog = DiagnosticBatch(main)
    private val lastSubsystemValues = mutableMapOf<String, String>()
    private val measurements = LeapmotorSomeIpMeasurementsState(SystemClock::elapsedRealtime)
    private val measurementsLog = DiagnosticBatch(main)
    private val lastMeasurementValues = mutableMapOf<String, String>()
    private val climate = LeapmotorSomeIpClimateState(SystemClock::elapsedRealtime)
    private val climateLog = DiagnosticBatch(main)
    private val tires = LeapmotorSomeIpTireState(SystemClock::elapsedRealtime)
    private val tireLog = DiagnosticBatch(main)
    private val qnxTires = LeapmotorQnxTireState(SystemClock::elapsedRealtime)
    private val qnxTireLog = DiagnosticBatch(main)
    private var lastQnxTireSummary: String? = null
    private val ambient = LeapmotorSomeIpAmbientState(SystemClock::elapsedRealtime)
    private val ambientLog = DiagnosticBatch(main)
    private val sunshade = LeapmotorSomeIpSunshadeState(SystemClock::elapsedRealtime)
    private val sunshadeLog = DiagnosticBatch(main)
    private val fan = LeapmotorSomeIpFanState(SystemClock::elapsedRealtime)
    private val fanLog = DiagnosticBatch(main)
    private val tireFlags = LeapmotorSomeIpTireFlagsState(SystemClock::elapsedRealtime)
    private val tireFlagsLog = DiagnosticBatch(main)
    private var binding: ServiceConnection? = null
    private var app: Context? = null
    private var connectionTimeout: Runnable? = null
    private val voiceListeners = IdentityHashMap<Any, (ByteArray?, Long) -> Unit>()
    @Volatile private var sourceStatus = "STOPPED"
    private val lastExterior = ReportedExteriorObservation(SystemClock::elapsedRealtime)

    /** Last received observation, not a fresh measurement. Cleared on source loss or invalid input. */
    internal fun lastReportedExterior(context: Context): ReportedExteriorObservation.Reading? {
        if (!enabled(context) || sourceStatus != "CONNECTED") return null
        return lastExterior.reading()
    }

    fun enabled(context: Context): Boolean = Build.VERSION.SDK_INT >= 28 && VehicleIntegrationPolicy.isLeapmotorC11(context)

    /** Cached read only; the host/controller owns the existing source lease. */
    fun snapshot(context: Context): VehicleStatusSnapshot? = if (enabled(context)) state.snapshot() else null

    /** OEM trip average, in the presentation unit documented by Button_604.csv.
     * Physical comparison is pending. Never derives consumption from battery percentage.
     */
    fun currentTripConsumption(context: Context): Double? =
        if (!enabled(context) || sourceStatus != "CONNECTED") null else measurements.readings()
            .first { it.channel == LeapmotorSomeIpMeasurementsState.Channel.CURRENT_TRIP_CONSUMPTION }
            .takeIf { it.status == LeapmotorSomeIpMeasurementsState.Status.OBSERVED_UNVERIFIED }
            ?.rawValue?.takeIf { it.isFinite() && it >= 0.0 }

    /** Read-only local cache; the visible host already owns the source lease. */
    fun tirePressures(context: Context): Map<LeapmotorQnxTireState.Wheel, Int?> =
        if (!enabled(context) || sourceStatus != "CONNECTED") emptyMap() else qnxTires.readings()
            .filter { it.channel.quantity == LeapmotorQnxTireState.Quantity.PRESSURE_KPA }
            .associate { it.channel.wheel to it.value }

    fun tireTemperatures(context: Context): Map<LeapmotorQnxTireState.Wheel, Int?> =
        if (!enabled(context) || sourceStatus != "CONNECTED") emptyMap() else qnxTires.readings()
            .filter { it.channel.quantity == LeapmotorQnxTireState.Quantity.TEMPERATURE_C }
            .associate { it.channel.wheel to it.value }

    /** Reported C11 exterior value, using the owner's Celsius-unit confirmation for this car.
     * The OEM sensor's health/sentinel contract remains unverified; no iPhone capability is advertised.
     */
    fun reportedExteriorCelsius(context: Context): Double? = if (!enabled(context)) null else
        reportedExteriorCelsius(ambient.readings().first {
            it.channel == LeapmotorSomeIpAmbientDecoder.Channel.EXTERIOR_CORRECTED
        })

    internal fun reportedExteriorCelsius(reading: LeapmotorSomeIpAmbientState.Reading): Double? =
        reading.rawValue?.takeIf {
            reading.channel == LeapmotorSomeIpAmbientDecoder.Channel.EXTERIOR_CORRECTED &&
                reading.status == LeapmotorSomeIpAmbientState.Status.OBSERVED_UNVERIFIED &&
                reading.receivedAgeMillis?.let { age -> age in 0..60_000 } == true &&
                it.isFinite() && it in -50.0..80.0
        }

    /** Reads memory only. Generating a report never starts or reconnects the source. */
    fun diagnosticReport(context: Context): String {
        if (!enabled(context)) return "C11 OEM telemetry: disabled for this vehicle profile"
        val snapshot = state.snapshot()
        return "C11 OEM telemetry: source=$sourceStatus reading=${if (snapshot == null) "UNAVAILABLE" else "AVAILABLE"}" +
            (snapshot?.let { " socPercent=${it.batteryPercent} rangeKm=${it.rangeKm}" } ?: "") +
            "\nfreshnessPolicy=LAST_RECEIVED_STATE originTimestamp=UNKNOWN silenceTimeoutMs=60000" +
            "\ncharging=UNKNOWN rangeWarning=UNKNOWN maxRangeKm=UNKNOWN" +
            "\nC11 charging readonly: source=$sourceStatus semantics=UNVERIFIED originTimestamp=UNKNOWN silenceTimeoutMs=60000" +
            charging.readings().joinToString(separator = "") { "\n${chargingDiagnostic(it)}" } +
            "\nC11 doors readonly: source=$sourceStatus semantics=UNVERIFIED originTimestamp=UNKNOWN silenceTimeoutMs=60000" +
            doors.readings().joinToString(separator = "") { "\n${doorDiagnostic(it)}" } +
            "\nC11 subsystems readonly: source=$sourceStatus semantics=UNVERIFIED originTimestamp=UNKNOWN silenceTimeoutMs=60000" +
            subsystems.readings().joinToString(separator = "") { "\n${subsystemDiagnostic(it)}" } +
            "\nC11 measurements readonly: source=$sourceStatus units=UNVERIFIED originTimestamp=UNKNOWN silenceTimeoutMs=60000" +
            measurements.readings().joinToString(separator = "") { "\n${measurementDiagnostic(it)}" } +
            "\nC11 local exterior: celsius=${lastReportedExterior(context)?.celsius ?: "UNKNOWN"}" +
            " receivedAgeMs=${lastReportedExterior(context)?.receivedAgeMillis ?: "UNKNOWN"} measurementAge=UNKNOWN" +
            "\nC11 HVAC readonly: source=$sourceStatus originTimestamp=UNKNOWN silenceTimeoutMs=60000 writeBackend=UNAVAILABLE" +
            climate.readings().joinToString(separator = "", prefix = "") { "\n${climateDiagnostic(it)}" } +
            "\nC11 TPMS readonly: source=$sourceStatus units=UNVERIFIED originTimestamp=UNKNOWN silenceTimeoutMs=60000" +
            tires.readings().joinToString(separator = "") { "\n${tireDiagnostic(it)}" } +
            "\nC11 QNX TPMS readonly: " + qnxTires.readings().joinToString(" ") {
                "${it.channel.wheel.name}/${it.channel.quantity.name}=${it.value ?: "UNKNOWN"}"
            } +
            "\nC11 TPMS flags readonly: source=$sourceStatus semantics=RAW_ZERO_CHECK sensorHealth=UNKNOWN originTimestamp=UNKNOWN silenceTimeoutMs=60000" +
            tireFlags.readings().joinToString(separator = "") { "\n${tireFlagsDiagnostic(it)}" } +
            "\n${fanDiagnostic(fan.reading())}" +
            "\nC11 AMBIENT readonly: source=$sourceStatus units=UNVERIFIED sensorValidity=UNKNOWN originTimestamp=UNKNOWN silenceTimeoutMs=60000" +
            ambient.readings().joinToString(separator = "") { "\n${ambientDiagnostic(it)}" } +
            "\nC11 sunshade readonly: source=$sourceStatus originTimestamp=UNKNOWN silenceTimeoutMs=60000 direction=UNVERIFIED writeBackend=UNAVAILABLE stop=UNKNOWN" +
            "\n${sunshadeDiagnostic(sunshade.reading())}" +
            "\n${LeapmotorExteriorTemperatureIntegration.diagnosticReport(context)}"
    }

    fun acquire(context: Context, owner: Any) {
        if (!enabled(context)) return
        // The authorized CarProperty source has its own lifetime; a SomeIP native crash must not
        // release an Activity/controller's exterior-temperature lease.
        LeapmotorExteriorTemperatureIntegration.acquire(context, owner)
        val application = context.applicationContext
        main.post {
            owners.add(owner)
            if (binding != null) return@post
            state.clear()
            charging.clear()
            chargingLog.reset()
            doors.clear()
            doorLog.reset()
            subsystems.clear(); subsystemLog.reset(); lastSubsystemValues.clear()
            measurements.clear(); measurementsLog.reset(); lastMeasurementValues.clear()
            climate.clear()
            climateLog.reset()
            tires.clear()
            tireLog.reset()
            qnxTires.clear()
            qnxTireLog.reset()
            lastQnxTireSummary = null
            ambient.clear()
            lastExterior.clear()
            ambientLog.reset()
            sunshade.clear()
            sunshadeLog.reset()
            fan.clear()
            fanLog.reset()
            tireFlags.clear()
            tireFlagsLog.reset()
            sourceStatus = "BINDING"
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (binding !== this) return
                    sourceStatus = "CONNECTED"
                    val chargingEpoch = charging.beginSource()
                    chargingLog.reset()
                    val doorEpoch = doors.beginSource()
                    doorLog.reset()
                    val subsystemEpoch = subsystems.beginSource()
                    subsystemLog.reset(); lastSubsystemValues.clear()
                    val measurementEpoch = measurements.beginSource()
                    measurementsLog.reset(); lastMeasurementValues.clear()
                    val climateEpoch = climate.beginSource()
                    climateLog.reset()
                    val ambientEpoch = ambient.beginSource()
                    lastExterior.clear()
                    ambientLog.reset()
                    val tireEpoch = tires.beginSource()
                    tireLog.reset()
                    val qnxTireEpoch = qnxTires.beginSource()
                    qnxTireLog.reset()
                    lastQnxTireSummary = null
                    val sunshadeEpoch = sunshade.beginSource()
                    sunshadeLog.reset()
                    val fanEpoch = fan.beginSource()
                    fanLog.reset()
                    val tireFlagsEpoch = tireFlags.beginSource()
                    tireFlagsLog.reset()
                    voiceListeners.values.toList().forEach { it(null, -1L) }
                    connectionTimeout?.let(main::removeCallbacks)
                    connectionTimeout = null
                    val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
                        if (binding === this && message.what == LeapmotorTelemetryService.EVENT) {
                            val fields = message.data
                            if (subsystems.receive(subsystemEpoch, fields.getInt("service"), fields.getInt("instance"),
                                fields.getInt("group", -1), fields.getInt("event"), fields.getByteArray("payload"),
                                fields.getLong("receivedAt", -1L))) {
                                subsystemLog.changed({ binding === this }) {
                                    subsystems.readings().forEach { reading ->
                                        val key = "${reading.module.name}/${reading.channel}"
                                        val value = "${reading.status.name}/${reading.rawValue}"
                                        if (lastSubsystemValues.put(key, value) != value) {
                                            android.util.Log.i("DiPlay-SUBSYSTEM", subsystemDiagnostic(reading))
                                        }
                                    }
                                }
                                // Sunshade 2801 also feeds its existing cache; do not consume it here.
                            }
                            if (measurements.receive(measurementEpoch, fields.getInt("service"), fields.getInt("instance"),
                                fields.getInt("group", -1), fields.getInt("event"), fields.getByteArray("payload"),
                                fields.getLong("receivedAt", -1L))) {
                                measurementsLog.changed({ binding === this }) {
                                    measurements.readings().forEach { reading ->
                                        val key = reading.channel.name
                                        val value = "${reading.status.name}/${reading.rawValue}"
                                        if (lastMeasurementValues.put(key, value) != value) {
                                            android.util.Log.i("DiPlay-MEASUREMENTS", measurementDiagnostic(reading))
                                        }
                                    }
                                }
                                return@Handler true
                            }
                            if (doors.receive(doorEpoch, fields.getInt("service"), fields.getInt("instance"),
                                fields.getInt("group", -1), fields.getInt("event"), fields.getByteArray("payload"),
                                fields.getLong("receivedAt", -1L))) {
                                doorLog.changed({ binding === this }) {
                                    doors.readings().forEach { reading ->
                                        android.util.Log.i("DiPlay-DOORS", doorDiagnostic(reading))
                                    }
                                }
                                return@Handler true
                            }
                            if (charging.receive(chargingEpoch, fields.getInt("service"), fields.getInt("instance"),
                                fields.getInt("group", -1), fields.getInt("event"), fields.getByteArray("payload"),
                                fields.getLong("receivedAt", -1L))) {
                                chargingLog.changed({ binding === this }) {
                                    charging.readings().forEach { reading ->
                                        android.util.Log.i("DiPlay-CHARGING", chargingDiagnostic(reading))
                                    }
                                }
                                return@Handler true
                            }
                            if (qnxTires.receive(qnxTireEpoch, fields.getInt("service"), fields.getInt("instance"),
                                fields.getInt("group", -1), fields.getInt("event"), fields.getByteArray("payload"),
                                fields.getLong("receivedAt", -1L))) {
                                qnxTireLog.changed({ binding === this }) {
                                    val summary = qnxTires.readings().joinToString(" ") {
                                        "${it.channel.wheel.name}/${it.channel.quantity.name}=${it.value ?: "UNKNOWN"}"
                                    }
                                    if (lastQnxTireSummary != summary) {
                                        lastQnxTireSummary = summary
                                        android.util.Log.i("DiPlay-QNX-TPMS", summary)
                                    }
                                }
                                return@Handler true
                            }
                            if (fan.receive(fanEpoch, fields.getInt("service"), fields.getInt("instance"),
                                fields.getInt("group", -1), fields.getInt("event"), fields.getByteArray("payload"),
                                fields.getLong("receivedAt", -1L))) {
                                fanLog.changed({ binding === this }) {
                                    android.util.Log.i("DiPlay-HVAC-FAN", fanDiagnostic(fan.reading()))
                                }
                                return@Handler true
                            }
                            if (tireFlags.receive(tireFlagsEpoch, fields.getInt("service"), fields.getInt("instance"),
                                fields.getInt("group", -1), fields.getInt("event"), fields.getByteArray("payload"),
                                fields.getLong("receivedAt", -1L))) {
                                tireFlagsLog.changed({ binding === this }) {
                                    tireFlags.readings().forEach { reading ->
                                        android.util.Log.i("DiPlay-TPMS-FLAGS", tireFlagsDiagnostic(reading))
                                    }
                                }
                                return@Handler true
                            }
                            if (sunshade.receive(sunshadeEpoch, fields.getInt("service"), fields.getInt("instance"),
                                fields.getInt("group", -1), fields.getInt("event"), fields.getByteArray("payload"),
                                fields.getLong("receivedAt", -1L))) {
                                sunshadeLog.changed({ binding === this }) {
                                    android.util.Log.i("DiPlay-SUNSHADE", sunshadeDiagnostic(sunshade.reading()))
                                }
                                return@Handler true
                            }
                            if (ambient.receive(ambientEpoch, fields.getInt("service"), fields.getInt("instance"),
                                fields.getInt("group", -1), fields.getInt("event"), fields.getByteArray("payload"),
                                fields.getLong("receivedAt", -1L))) {
                                if (fields.getInt("event") == LeapmotorSomeIpAmbientDecoder.EXTERIOR_CORRECTED_EVENT) {
                                    val reading = ambient.readings().single {
                                        it.channel == LeapmotorSomeIpAmbientDecoder.Channel.EXTERIOR_CORRECTED
                                    }
                                    lastExterior.receive(reading, fields.getLong("receivedAt", -1L))
                                }
                                ambientLog.changed({ binding === this }) {
                                    ambient.readings().forEach { reading ->
                                        android.util.Log.i("DiPlay-AMBIENT", ambientDiagnostic(reading))
                                    }
                                }
                                return@Handler true
                            }
                            if (tires.receive(tireEpoch, fields.getInt("service"), fields.getInt("instance"),
                                fields.getInt("group", -1), fields.getInt("event"), fields.getByteArray("payload"),
                                fields.getLong("receivedAt", -1L))) {
                                tireLog.changed({ binding === this }) {
                                    tires.readings().forEach { reading ->
                                        android.util.Log.i("DiPlay-TPMS", tireDiagnostic(reading))
                                    }
                                }
                                return@Handler true
                            }
                            if (climate.receive(climateEpoch, fields.getInt("service"), fields.getInt("instance"),
                                fields.getInt("event"), fields.getByteArray("payload"), fields.getLong("receivedAt", -1L))) {
                                climateLog.changed({ binding === this }) {
                                    climate.readings().forEach { reading ->
                                        android.util.Log.i("DiPlay-HVAC", climateDiagnostic(reading))
                                    }
                                }
                                return@Handler true
                            }
                            if (fields.getInt("service") == 4161 && fields.getInt("instance") == 20545 &&
                                fields.getInt("event") == 4114) {
                                android.util.Log.i("DiPlay-C11-Voice", "wheel event received listeners=${voiceListeners.size}")
                                voiceListeners.values.toList().forEach {
                                    it(fields.getByteArray("payload"), fields.getLong("receivedAt", -1L))
                                }
                                return@Handler true
                            }
                            state.receive(fields.getInt("service"), fields.getInt("instance"), fields.getInt("event"),
                                fields.getByteArray("payload"), fields.getLong("receivedAt", -1L))
                        }
                        true
                    })
                    try {
                        Messenger(service).send(Message.obtain(null, LeapmotorTelemetryService.SUBSCRIBE).apply { replyTo = replies })
                    } catch (_: RemoteException) { failed(this) }
                }
                override fun onServiceDisconnected(name: ComponentName) = failed(this)
                override fun onBindingDied(name: ComponentName) = failed(this)
                override fun onNullBinding(name: ComponentName) = failed(this)
            }
            app = application
            binding = connection
            connectionTimeout = Runnable { failed(connection) }.also { main.postDelayed(it, 8_000L) }
            try {
                if (!application.bindService(Intent(application, LeapmotorTelemetryService::class.java), connection, Context.BIND_AUTO_CREATE)) {
                    failed(connection)
                }
            } catch (_: RuntimeException) { failed(connection) }
        }
    }

    fun release(owner: Any) {
        LeapmotorExteriorTemperatureIntegration.release(owner)
        main.post { owners.remove(owner); if (owners.isEmpty()) binding?.let(::failed) }
    }

    /** Listening adds no source lease; the visible host already owns one. */
    internal fun listenForVoiceButton(owner: Any, listener: (ByteArray?, Long) -> Unit) {
        voiceListeners[owner] = listener
    }

    internal fun stopListeningForVoiceButton(owner: Any) { voiceListeners.remove(owner) }

    fun provider(context: Context): ManagedVehicleStatusProvider {
        val allowed = enabled(context)
        val owner = Any()
        acquire(context, owner)
        return object : ManagedVehicleStatusProvider {
            @Volatile private var closed = false
            override fun snapshot(): VehicleStatusSnapshot? = if (closed || !allowed) null else state.snapshot()
            override fun close() { closed = true; release(owner) }
        }
    }

    private fun failed(connection: ServiceConnection) {
        if (binding !== connection) return
        binding = null
        sourceStatus = if (owners.isEmpty()) "STOPPED" else "DISCONNECTED"
        connectionTimeout?.let(main::removeCallbacks)
        connectionTimeout = null
        state.clear()
        charging.clear()
        chargingLog.reset()
        doors.clear()
        doorLog.reset()
        subsystems.clear(); subsystemLog.reset(); lastSubsystemValues.clear()
        measurements.clear(); measurementsLog.reset(); lastMeasurementValues.clear()
        climate.clear()
        climateLog.reset()
        tires.clear()
        tireLog.reset()
        qnxTires.clear()
        qnxTireLog.reset()
        lastQnxTireSummary = null
        ambient.clear()
        lastExterior.clear()
        ambientLog.reset()
        sunshade.clear()
        sunshadeLog.reset()
        fan.clear()
        fanLog.reset()
        tireFlags.clear()
        tireFlagsLog.reset()
        voiceListeners.values.toList().forEach { it(null, -1L) }
        runCatching { app?.unbindService(connection) }
        app = null
        // No automatic restart loop after a native crash. A new acquire retries explicitly.
    }

    private fun subsystemDiagnostic(reading: LeapmotorSomeIpSubsystemState.Reading): String =
        "C11 SUBSYSTEM module=${reading.module.name} channel=${reading.channel} status=${reading.status.name}" +
            " raw=${reading.rawValue?.toString() ?: "UNKNOWN"}" +
            " receivedAgeMs=${reading.receivedAgeMillis?.toString() ?: "UNKNOWN"}"

    private fun measurementDiagnostic(reading: LeapmotorSomeIpMeasurementsState.Reading): String =
        "C11 MEASUREMENT channel=${reading.channel.name} status=${reading.status.name}" +
            " raw=${reading.rawValue?.toString() ?: "UNKNOWN"}" +
            " receivedAgeMs=${reading.receivedAgeMillis?.toString() ?: "UNKNOWN"}"

    private fun doorDiagnostic(reading: LeapmotorSomeIpDoorState.Reading): String =
        "C11 DOORS status=${reading.status.name} channel=${reading.channel.name}" +
            " raw=${reading.rawValue?.toString() ?: "UNKNOWN"}" +
            " receivedAgeMs=${reading.receivedAgeMillis?.toString() ?: "UNKNOWN"}"

    private fun chargingDiagnostic(reading: LeapmotorSomeIpChargingState.Reading): String =
        "C11 CHARGING status=${reading.status.name} channel=${reading.channel.name}" +
            " raw=${reading.rawValue?.toString() ?: "UNKNOWN"}" +
            " receivedAgeMs=${reading.receivedAgeMillis?.toString() ?: "UNKNOWN"}"

    private fun fanDiagnostic(reading: LeapmotorSomeIpFanState.Reading): String =
        "C11 HVAC_FAN status=${if (reading.status == LeapmotorSomeIpFanState.Status.AVAILABLE) "OBSERVED" else reading.status.name}" +
            " level=${reading.level?.toString() ?: "UNKNOWN"}" +
            " receivedAgeMs=${reading.receivedAgeMillis?.toString() ?: "UNKNOWN"}"

    private fun tireFlagsDiagnostic(reading: LeapmotorSomeIpTireFlagsState.Reading): String {
        fun field(kind: LeapmotorSomeIpTireFlagsDecoder.Kind, key: String): String {
            val value = reading.flags.single { it.channel.kind == kind }
            return " ${key}Raw=${value.rawValue?.toString() ?: "UNKNOWN"}" +
                " ${key}AgeMs=${value.receivedAgeMillis?.toString() ?: "UNKNOWN"}"
        }
        return "C11 TPMS_FLAGS wheel=${reading.wheel.name} pressureCheck=${reading.pressureCheck.name}" +
            " temperatureCheck=${reading.temperatureCheck.name}" +
            field(LeapmotorSomeIpTireFlagsDecoder.Kind.LEAK, "leak") +
            field(LeapmotorSomeIpTireFlagsDecoder.Kind.PRESSURE, "pressure") +
            field(LeapmotorSomeIpTireFlagsDecoder.Kind.TEMPERATURE, "temperature")
    }

    private fun climateDiagnostic(reading: LeapmotorSomeIpClimateState.Reading): String =
        "C11 HVAC status=${reading.status.name} side=${reading.side.name}" +
            " celsius=${reading.celsius?.toString() ?: "UNKNOWN"}" +
            " receivedAgeMs=${reading.receivedAgeMillis?.toString() ?: "UNKNOWN"}"

    private fun sunshadeDiagnostic(reading: LeapmotorSomeIpSunshadeState.Reading): String =
        "C11 SUNSHADE status=${reading.status.name}" +
            " positionRaw=${reading.rawPosition?.toString() ?: "UNKNOWN"}" +
            " oemUiPosition=${reading.oemUiPosition?.toString() ?: "UNKNOWN"}" +
            " receivedAgeMs=${reading.receivedAgeMillis?.toString() ?: "UNKNOWN"}"

    private fun ambientDiagnostic(reading: LeapmotorSomeIpAmbientState.Reading): String {
        val key = if (reading.channel == LeapmotorSomeIpAmbientDecoder.Channel.INTERIOR)
            "interiorRawDouble" else "exteriorCorrectedRawDouble"
        // A finite Double's canonical form is bounded; no arbitrary OEM text reaches the log.
        return "C11 AMBIENT status=${reading.status.name} channel=${reading.channel.name}" +
            " $key=${reading.rawValue?.toString() ?: "UNKNOWN"}" +
            " receivedAgeMs=${reading.receivedAgeMillis?.toString() ?: "UNKNOWN"}"
    }

    private fun tireDiagnostic(reading: LeapmotorSomeIpTireState.Reading): String {
        val key = if (reading.channel.quantity == LeapmotorSomeIpTireDecoder.Quantity.RAW_PRESSURE)
            "pressureRaw" else "tempRaw"
        return "C11 TPMS status=${reading.status.name} wheel=${reading.channel.wheel.name}" +
            " $key=${reading.rawValue?.toString() ?: "UNKNOWN"}" +
            " receivedAgeMs=${reading.receivedAgeMillis?.toString() ?: "UNKNOWN"}"
    }

    /** Main-thread only: immediate first batch and one trailing batch of the latest cached state. */
    private class DiagnosticBatch(private val handler: Handler) {
        private var generation = 0L
        private var lastEmissionAt: Long? = null
        private var pending: Runnable? = null

        fun reset() {
            generation++
            pending?.let(handler::removeCallbacks)
            pending = null
            lastEmissionAt = null
        }

        fun changed(currentSource: () -> Boolean, emitLatestCache: () -> Unit) {
            if (!currentSource()) return
            val now = SystemClock.elapsedRealtime()
            val previous = lastEmissionAt
            if (previous == null || now < previous || now - previous >= 1_000L) {
                pending?.let(handler::removeCallbacks)
                pending = null
                lastEmissionAt = now
                emitLatestCache()
            } else if (pending == null) {
                val scheduledGeneration = generation
                val task = Runnable {
                    if (generation == scheduledGeneration) {
                        pending = null
                        if (currentSource()) {
                            lastEmissionAt = SystemClock.elapsedRealtime()
                            emitLatestCache()
                        }
                    }
                }
                pending = task
                handler.postDelayed(task, 1_000L - (now - previous))
            }
        }
    }
}
