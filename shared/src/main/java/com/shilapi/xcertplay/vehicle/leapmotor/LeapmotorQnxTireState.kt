package com.shilapi.xcertplay.vehicle.leapmotor

/** OEM QNX widget values, distinct from the unscaled direct-CAN channels.
 * Position mapping comes from LeapCenterManager; kPa is confirmed on this C11's OEM display.
 * Only status 0 is accepted. Other flags withdraw a value without inferring an alarm or health.
 * Receipt time is local; the vehicle does not provide a sensor measurement timestamp here.
 */
class LeapmotorQnxTireState(private val elapsedMillis: () -> Long) {
    enum class Wheel { FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT }
    enum class Quantity { PRESSURE_KPA, TEMPERATURE_C }
    data class Channel(val wheel: Wheel, val quantity: Quantity)
    data class Reading(val channel: Channel, val value: Int?, val receivedAgeMillis: Long?)
    private data class Field(var value: Int? = null, var at: Long? = null)
    private val fields = (1109..1116).associateWith { Field() }
    private var epoch = 0L
    private var active = false

    @Synchronized fun beginSource(): Long { clear(); active = true; return epoch }
    @Synchronized fun clear() {
        epoch++
        active = false
        fields.values.forEach { it.value = null; it.at = null }
    }

    @Synchronized fun receive(sourceEpoch: Long, service: Int, instance: Int, group: Int,
        event: Int, payload: ByteArray?, at: Long): Boolean {
        if (!active || epoch != sourceEpoch || service != 29185 || instance != 29186 || group != 29184) return false
        val field = fields[event] ?: return false
        val now = elapsedMillis()
        if (at < 0 || at > now || now - at > SILENCE_MS || field.at?.let { at < it } == true) {
            field.value = null
            return true
        }
        field.at = at
        val text = payload?.takeIf { it.size in 3..16 && it.all { b -> b in 32..126 } }
            ?.toString(Charsets.US_ASCII)
        val number = text?.takeIf { VALUE.matches(it) }?.substringBefore('|')?.toIntOrNull()
        field.value = number?.takeIf {
            if (event <= 1112) it in 0..4094 else it in -50..150
        }
        return true
    }

    @Synchronized fun readings(): List<Reading> {
        val now = elapsedMillis()
        return fields.map { (event, field) ->
            val age = field.at?.takeIf { it <= now }?.let { now - it }
            if (age == null || age > SILENCE_MS) field.value = null
            Reading(Channel(Wheel.entries[(event - 1109) % 4],
                if (event <= 1112) Quantity.PRESSURE_KPA else Quantity.TEMPERATURE_C), field.value, age)
        }
    }

    companion object {
        private const val SILENCE_MS = 60_000L
        private val VALUE = Regex("-?[0-9]{1,4}\\|0")
    }
}
