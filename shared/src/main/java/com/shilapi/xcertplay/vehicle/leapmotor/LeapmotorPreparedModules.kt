package com.shilapi.xcertplay.vehicle.leapmotor

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Prepared raw contracts wired only to passive diagnostics, never UI, Siri or vehicle status.
 * IDs are OEM SOME/IP events, never Android properties, CAN IDs or writable SDK commands.
 * See docs/LEAPMOTOR_C11_PREPARED_MODULES.md for the inspected consumers and remaining checks.
 */
internal object LeapmotorPreparedModules {
    const val SERVICE = 0x9900
    const val INSTANCE = 0x9901
    const val GROUP = 0x9903

    // Owner's requested order; climate work is deferred until the other modules.
    enum class Module { CHARGING, DOORS_LOCKS, WINDOWS_ROOF, SEATS, LIGHTS, ENERGY,
        WIPERS, DRIVING, WARNINGS, CLIMATE }

    /** All values remain raw, including charge=1: static interpretation is not a live validation. */
    enum class Signal(val module: Module, val event: Int, val position: Boolean = false,
        val allowedCodes: IntRange? = null, val invalidCodes: Set<Int> = emptySet()) {
        CHARGE_STATUS(Module.CHARGING, 5702),
        AC_CONNECTION(Module.CHARGING, 6207),
        DC_CONNECTION(Module.CHARGING, 5905),
        AC_PLUG_LOCK_STATUS(Module.CHARGING, 2509),
        ONBOARD_CHARGER_STATUS(Module.CHARGING, 6100),
        CHARGE_TIME_REMAINING(Module.CHARGING, 5908, invalidCodes = setOf(0xffffff)),
        CHARGE_TARGET_PERCENT(Module.CHARGING, 11307, allowedCodes = 50..100),
        DRIVER_DOOR(Module.DOORS_LOCKS, 3860),
        PASSENGER_DOOR(Module.DOORS_LOCKS, 3820),
        REAR_LEFT_DOOR(Module.DOORS_LOCKS, 3821),
        REAR_RIGHT_DOOR(Module.DOORS_LOCKS, 3822),
        TAILGATE_STATUS(Module.DOORS_LOCKS, 3823),
        TAILGATE_POSITION(Module.DOORS_LOCKS, 815, true),
        POWER_TAILGATE_STATUS(Module.DOORS_LOCKS, 802),
        HOOD_STATUS(Module.DOORS_LOCKS, 3824, allowedCodes = 0..1),
        VEHICLE_LOCK_STATUS(Module.DOORS_LOCKS, 304),
        REAR_LEFT_CHILD_LOCK_STATUS(Module.DOORS_LOCKS, 610),
        REAR_RIGHT_CHILD_LOCK_STATUS(Module.DOORS_LOCKS, 511),
        FRONT_LEFT_WINDOW_POSITION(Module.WINDOWS_ROOF, 2900, true),
        FRONT_RIGHT_WINDOW_POSITION(Module.WINDOWS_ROOF, 3000, true),
        REAR_LEFT_WINDOW_POSITION(Module.WINDOWS_ROOF, 2901, true),
        REAR_RIGHT_WINDOW_POSITION(Module.WINDOWS_ROOF, 3001, true),
        GLASS_ROOF_STATUS(Module.WINDOWS_ROOF, 2800),
        SUNSHADE_POSITION(Module.WINDOWS_ROOF, 2801, true),
        LEFT_MIRROR_HEAT_STATUS(Module.WINDOWS_ROOF, 1700),
        RIGHT_MIRROR_HEAT_STATUS(Module.WINDOWS_ROOF, 1800),
        FRONT_LEFT_SEAT_SLIDE_POSITION(Module.SEATS, 2300, true),
        FRONT_LEFT_SEAT_BASE_FRONT_POSITION(Module.SEATS, 2302, true),
        FRONT_LEFT_SEAT_BACKREST_POSITION(Module.SEATS, 2306, true),
        DRIVER_SEAT_HEAT(Module.SEATS, 10500, allowedCodes = 0..3),
        PASSENGER_SEAT_HEAT(Module.SEATS, 10501, allowedCodes = 0..3),
        REAR_LEFT_SEAT_HEAT(Module.SEATS, 10502, allowedCodes = 0..3),
        REAR_RIGHT_SEAT_HEAT(Module.SEATS, 10503, allowedCodes = 0..3),
        DRIVER_SEAT_VENT(Module.SEATS, 10504, allowedCodes = 0..3),
        PASSENGER_SEAT_VENT(Module.SEATS, 10505, allowedCodes = 0..3),
        REAR_LEFT_SEAT_VENT(Module.SEATS, 10506, allowedCodes = 0..3),
        REAR_RIGHT_SEAT_VENT(Module.SEATS, 10507, allowedCodes = 0..3),
        FRONT_PRIMARY_OCCUPANCY_STATUS(Module.SEATS, 3806),
        FRONT_SECONDARY_OCCUPANCY_STATUS(Module.SEATS, 3815),
        LOW_BEAM_STATUS(Module.LIGHTS, 3102),
        HIGH_BEAM_STATUS(Module.LIGHTS, 3814),
        MAIN_LAMP_FEEDBACK(Module.LIGHTS, 3813),
        REAR_FOG_STATUS(Module.LIGHTS, 2727),
        LEFT_TURN_SWITCH(Module.LIGHTS, 3807),
        RIGHT_TURN_SWITCH(Module.LIGHTS, 3808),
        REAR_LEFT_READING_LIGHT_STATUS(Module.LIGHTS, 2702),
        REAR_RIGHT_READING_LIGHT_STATUS(Module.LIGHTS, 2703),
        CLTC_RANGE(Module.ENERGY, 11102, allowedCodes = 0..Int.MAX_VALUE),
        WLTC_RANGE(Module.ENERGY, 11103, allowedCodes = 0..Int.MAX_VALUE),
        RANGE_STANDARD(Module.ENERGY, 11104),
        FRONT_WIPER_MAINTENANCE_OFF_FAILURE(Module.WIPERS, 1521),
        FRONT_WIPER_MAINTENANCE_ON_FAILURE(Module.WIPERS, 1522),
        FRONT_WIPER_START_FAILURE(Module.WIPERS, 1523),
        REAR_WIPER_MAINTENANCE_ON_FAILURE(Module.WIPERS, 1524),
        REAR_WIPER_MAINTENANCE_OFF_FAILURE(Module.WIPERS, 1525),
        REAR_WIPER_START_FAILURE(Module.WIPERS, 1526),
        GEAR(Module.DRIVING, 5211),
        READY_STATUS(Module.DRIVING, 5301),
        BRAKE_CONTROLLER_STATUS(Module.DRIVING, 4101),
        FRONT_PRIMARY_BELT(Module.WARNINGS, 9004),
        FRONT_SECONDARY_BELT(Module.WARNINGS, 9005),
        REAR_LEFT_BELT(Module.WARNINGS, 9006),
        REAR_MIDDLE_BELT(Module.WARNINGS, 9007),
        REAR_RIGHT_BELT(Module.WARNINGS, 9008),
        REAR_HVAC_POWER_STATUS(Module.CLIMATE, 9801, allowedCodes = 0..1),
        REAR_HVAC_FAN_LEVEL(Module.CLIMATE, 9805, allowedCodes = 0..7),
        REAR_HVAC_AIR_DISTRIBUTION(Module.CLIMATE, 9802),
        REAR_HVAC_AUTO_STATUS(Module.CLIMATE, 9803, allowedCodes = 0..1),
        CABIN_PM25_LEVEL(Module.CLIMATE, 1107, allowedCodes = 1..6),
        HVAC_AUTO_CLEAR_STATUS(Module.CLIMATE, 1217),
        HVAC_SMART_CLEAR_STATUS(Module.CLIMATE, 1219),
        HVAC_SYNC_FEEDBACK(Module.CLIMATE, 7606),
        HVAC_POWER_STATUS(Module.CLIMATE, 1001),
        HVAC_AUTO_STATUS(Module.CLIMATE, 1002),
        HVAC_AC_STATUS(Module.CLIMATE, 1003),
        HVAC_AIR_DISTRIBUTION(Module.CLIMATE, 1011),
        HVAC_FRONT_DEFROST(Module.CLIMATE, 1012),
        HVAC_REAR_DEFROST(Module.CLIMATE, 1013),
        HVAC_RECIRCULATION_STATUS(Module.CLIMATE, 1010),
        HVAC_RECIRCULATION_AUTO_STATUS(Module.CLIMATE, 1310),
    }

    private val byEvent = Signal.entries.associateBy { it.event }
    fun signals(module: Module): List<Signal> = Signal.entries.filter { it.module == module }
    fun signal(service: Int, instance: Int, group: Int, event: Int): Signal? =
        if (service == SERVICE && instance == INSTANCE && group == GROUP) byEvent[event] else null

    data class Observation(val signal: Signal, val rawValue: Int?)

    /** Unrelated input returns null; malformed input for a known signal withdraws its old value. */
    fun decode(service: Int, instance: Int, group: Int, event: Int, payload: ByteArray?): Observation? {
        val signal = signal(service, instance, group, event) ?: return null
        val raw = payload?.takeIf { it.size == 4 }?.let {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int
        }?.takeIf { (!signal.position || it in 0..100) &&
            (signal.allowedCodes == null || it in signal.allowedCodes) && it !in signal.invalidCodes }
        return Observation(signal, raw)
    }
}

/** Per-field receipt lifetime for offline replay and a future transport owner.
 * No timers, connections or commands are started. Receipt time is not the measurement time.
 */
internal class LeapmotorPreparedModuleState(
    private val elapsedMillis: () -> Long,
    private val silenceMillis: Long = 60_000L,
) {
    enum class Status { UNAVAILABLE, OBSERVED_UNVERIFIED, STALE }
    data class Reading(val signal: LeapmotorPreparedModules.Signal, val status: Status,
        val rawValue: Int?, val receivedAgeMillis: Long?)
    private data class Field(var raw: Int? = null, var at: Long? = null,
        var status: Status = Status.UNAVAILABLE)
    private val fields = LeapmotorPreparedModules.Signal.entries.associateWith { Field() }
    private var epoch = 0L
    private var active = false

    init { require(silenceMillis > 0) }

    @Synchronized fun beginSource(): Long { clear(); active = true; return epoch }
    @Synchronized fun clear() {
        epoch++
        active = false
        fields.values.forEach { it.raw = null; it.at = null; it.status = Status.UNAVAILABLE }
    }

    @Synchronized fun receive(sourceEpoch: Long, service: Int, instance: Int, group: Int,
        event: Int, payload: ByteArray?, receivedAtMillis: Long): Boolean {
        if (!active || sourceEpoch != epoch) return false
        val observation = LeapmotorPreparedModules.decode(service, instance, group, event, payload) ?: return false
        val field = fields.getValue(observation.signal)
        val now = elapsedMillis()
        val previous = field.at
        if (receivedAtMillis < 0 || receivedAtMillis > now || now - receivedAtMillis > silenceMillis ||
            previous != null && receivedAtMillis < previous) {
            field.raw = null
            field.status = Status.UNAVAILABLE
            return true
        }
        field.at = receivedAtMillis
        field.raw = observation.rawValue
        field.status = if (field.raw == null) Status.UNAVAILABLE else Status.OBSERVED_UNVERIFIED
        return true
    }

    @Synchronized fun readings(module: LeapmotorPreparedModules.Module): List<Reading> {
        val now = elapsedMillis()
        return LeapmotorPreparedModules.signals(module).map { signal ->
            val field = fields.getValue(signal)
            val age = field.at?.takeIf { now >= it }?.let { now - it }
            if (field.raw != null && (age == null || age > silenceMillis)) {
                field.raw = null
                field.status = Status.STALE
            }
            Reading(signal, field.status, field.raw, age)
        }
    }
}
