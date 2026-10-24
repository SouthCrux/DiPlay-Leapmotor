package com.shilapi.xcertplay.vehicle.leapmotor

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** OEM numeric observations. OEM presentation units remain physically unverified; no speed interlock.
 * No energy/capacity estimates, consumption calculation, vehicle control or iPhone advertisement.
 */
class LeapmotorSomeIpMeasurementsState(
    private val elapsedMillis: () -> Long,
    private val silenceMillis: Long = 60_000L,
) {
    enum class Route(val service: Int, val instance: Int, val group: Int) {
        CAN(0x9900, 0x9901, 0x9903), CLUSTER(4161, 20545, 17920),
    }
    enum class Wire(val bytes: Int) { INT32(4), DOUBLE(8) }
    enum class OemUnit { UNVERIFIED, KILOMETRES, KWH_PER_100_KM, MINUTES, CELSIUS }
    enum class Channel(val route: Route, val event: Int, val wire: Wire = Wire.DOUBLE,
        val oemUnit: OemUnit = OemUnit.UNVERIFIED,
        val validRange: ClosedFloatingPointRange<Double>? = null) {
        CURRENT_TRIP_DISTANCE(Route.CLUSTER, 12289, oemUnit = OemUnit.KILOMETRES),
        CURRENT_TRIP_CONSUMPTION(Route.CLUSTER, 12291, oemUnit = OemUnit.KWH_PER_100_KM),
        RECHARGE_TRIP_DISTANCE(Route.CLUSTER, 12295, oemUnit = OemUnit.KILOMETRES),
        RECHARGE_TRIP_CONSUMPTION(Route.CLUSTER, 12297, oemUnit = OemUnit.KWH_PER_100_KM),
        TRIP_A_DISTANCE(Route.CLUSTER, 12307, oemUnit = OemUnit.KILOMETRES),
        TRIP_A_CONSUMPTION(Route.CLUSTER, 12309, oemUnit = OemUnit.KWH_PER_100_KM),
        VEHICLE_SPEED(Route.CAN, 4030),
        REAR_HVAC_DISPLAY_TEMPERATURE(Route.CAN, 9800, oemUnit = OemUnit.CELSIUS,
            validRange = 18.0..32.0),
        CURRENT_TRIP_DURATION(Route.CLUSTER, 12290, Wire.INT32, OemUnit.MINUTES),
        RECHARGE_TRIP_DURATION(Route.CLUSTER, 12296, Wire.INT32, OemUnit.MINUTES),
        TRIP_A_DURATION(Route.CLUSTER, 12308, Wire.INT32, OemUnit.MINUTES),
    }
    enum class Status { UNAVAILABLE, OBSERVED_UNVERIFIED, STALE }
    data class Reading(val channel: Channel, val status: Status, val rawValue: Double?,
        val receivedAgeMillis: Long?)
    private data class Field(var raw: Double? = null, var at: Long? = null,
        var status: Status = Status.UNAVAILABLE)
    private val fields = Channel.entries.associateWith { Field() }
    private var epoch = 0L
    private var active = false
    init { require(silenceMillis > 0) }

    @Synchronized fun beginSource(): Long { clear(); active = true; return epoch }
    @Synchronized fun clear() {
        epoch++; active = false
        fields.values.forEach { it.raw = null; it.at = null; it.status = Status.UNAVAILABLE }
    }
    @Synchronized fun receive(sourceEpoch: Long, service: Int, instance: Int, group: Int,
        event: Int, payload: ByteArray?, receivedAtMillis: Long): Boolean {
        if (!active || sourceEpoch != epoch) return false
        val channel = channel(service, instance, group, event) ?: return false
        val field = fields.getValue(channel)
        val now = elapsedMillis()
        val previous = field.at
        if (receivedAtMillis < 0 || receivedAtMillis > now || now - receivedAtMillis > silenceMillis ||
            previous != null && receivedAtMillis < previous) {
            field.raw = null; field.status = Status.UNAVAILABLE
            return true
        }
        field.at = receivedAtMillis
        field.raw = payload?.takeIf { it.size == channel.wire.bytes }?.let {
            val buffer = ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
            when (channel.wire) {
                Wire.DOUBLE -> buffer.double
                Wire.INT32 -> buffer.int.toDouble()
            }
        }?.takeIf { it.isFinite() && (channel.wire != Wire.INT32 || it >= 0) &&
            (channel.validRange == null || it in channel.validRange) }
        field.status = if (field.raw == null) Status.UNAVAILABLE else Status.OBSERVED_UNVERIFIED
        return true
    }
    @Synchronized fun readings(): List<Reading> {
        val now = elapsedMillis()
        return fields.map { (channel, field) ->
            val age = field.at?.takeIf { now >= it }?.let { now - it }
            if (field.raw != null && (age == null || age > silenceMillis)) {
                field.raw = null; field.status = Status.STALE
            }
            Reading(channel, field.status, field.raw, age)
        }
    }
    companion object {
        fun channel(service: Int, instance: Int, group: Int, event: Int): Channel? = Channel.entries
            .firstOrNull { it.event == event && it.route.service == service &&
                it.route.instance == instance && it.route.group == group }
        fun eventIds(route: Route): IntArray = Channel.entries.filter { it.route == route }
            .map { it.event }.toIntArray()
    }
}
