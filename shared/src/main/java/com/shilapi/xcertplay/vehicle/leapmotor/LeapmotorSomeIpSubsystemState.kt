package com.shilapi.xcertplay.vehicle.leapmotor

/** Remaining integer modules, read-only and diagnostic-only. All names come from fixed OEM
 * consumer mappings. No state here authorizes a command, reports a safety verdict or enables Siri.
 */
class LeapmotorSomeIpSubsystemState(elapsedMillis: () -> Long) {
    enum class Module { WINDOWS_ROOF, SEATS, LIGHTS, ENERGY, WIPERS, DRIVING, WARNINGS, CLIMATE }
    enum class Status { UNAVAILABLE, OBSERVED_UNVERIFIED, STALE }
    data class Reading(val module: Module, val channel: String, val status: Status,
        val rawValue: Int?, val receivedAgeMillis: Long?)
    private val state = LeapmotorPreparedModuleState(elapsedMillis)
    fun beginSource(): Long = state.beginSource()
    fun clear() = state.clear()
    fun receive(sourceEpoch: Long, service: Int, instance: Int, group: Int, event: Int,
        payload: ByteArray?, receivedAtMillis: Long): Boolean =
        accepts(service, instance, group, event) &&
            state.receive(sourceEpoch, service, instance, group, event, payload, receivedAtMillis)
    fun readings(): List<Reading> = Module.entries.flatMap { module ->
        state.readings(LeapmotorPreparedModules.Module.valueOf(module.name)).map {
            Reading(module, it.signal.name, when (it.status) {
                LeapmotorPreparedModuleState.Status.UNAVAILABLE -> Status.UNAVAILABLE
                LeapmotorPreparedModuleState.Status.OBSERVED_UNVERIFIED -> Status.OBSERVED_UNVERIFIED
                LeapmotorPreparedModuleState.Status.STALE -> Status.STALE
            }, it.rawValue, it.receivedAgeMillis)
        }
    }
    companion object {
        fun eventIds(): IntArray = LeapmotorPreparedModules.Signal.entries
            .filter { signal -> Module.entries.any { it.name == signal.module.name } }
            .map { it.event }.toIntArray()
        fun accepts(service: Int, instance: Int, group: Int, event: Int): Boolean =
            LeapmotorPreparedModules.signal(service, instance, group, event)?.let { signal ->
                Module.entries.any { it.name == signal.module.name }
            } == true
    }
}
