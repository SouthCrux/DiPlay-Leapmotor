package com.shilapi.xcertplay.vehicle.leapmotor

/** Read-only charging facade. Accepts only the charging subset of the prepared modules.
 * Raw codes are observations, not a boolean charging or connector-availability contract.
 */
class LeapmotorSomeIpChargingState(elapsedMillis: () -> Long) {
    enum class Channel(val event: Int) {
        CHARGE_STATUS(LeapmotorPreparedModules.Signal.CHARGE_STATUS.event),
        AC_CONNECTION(LeapmotorPreparedModules.Signal.AC_CONNECTION.event),
        DC_CONNECTION(LeapmotorPreparedModules.Signal.DC_CONNECTION.event),
        AC_PLUG_LOCK_STATUS(LeapmotorPreparedModules.Signal.AC_PLUG_LOCK_STATUS.event),
        ONBOARD_CHARGER_STATUS(LeapmotorPreparedModules.Signal.ONBOARD_CHARGER_STATUS.event),
        CHARGE_TIME_REMAINING(LeapmotorPreparedModules.Signal.CHARGE_TIME_REMAINING.event),
        CHARGE_TARGET_PERCENT(LeapmotorPreparedModules.Signal.CHARGE_TARGET_PERCENT.event),
    }
    enum class Status { UNAVAILABLE, OBSERVED_UNVERIFIED, STALE }
    data class Reading(val channel: Channel, val status: Status, val rawValue: Int?,
        val receivedAgeMillis: Long?)

    private val state = LeapmotorPreparedModuleState(elapsedMillis)
    fun beginSource(): Long = state.beginSource()
    fun clear() = state.clear()
    fun receive(sourceEpoch: Long, service: Int, instance: Int, group: Int, event: Int,
        payload: ByteArray?, receivedAtMillis: Long): Boolean =
        accepts(service, instance, group, event) &&
            state.receive(sourceEpoch, service, instance, group, event, payload, receivedAtMillis)

    fun readings(): List<Reading> = state.readings(LeapmotorPreparedModules.Module.CHARGING).map {
        Reading(Channel.entries.single { channel -> channel.event == it.signal.event }, when (it.status) {
            LeapmotorPreparedModuleState.Status.UNAVAILABLE -> Status.UNAVAILABLE
            LeapmotorPreparedModuleState.Status.OBSERVED_UNVERIFIED -> Status.OBSERVED_UNVERIFIED
            LeapmotorPreparedModuleState.Status.STALE -> Status.STALE
        }, it.rawValue, it.receivedAgeMillis)
    }

    companion object {
        const val SERVICE = 0x9900
        const val INSTANCE = 0x9901
        const val GROUP = 0x9903
        fun eventIds(): IntArray = Channel.entries.map { it.event }.toIntArray()
        fun accepts(service: Int, instance: Int, group: Int, event: Int): Boolean =
            service == SERVICE && instance == INSTANCE && group == GROUP &&
                Channel.entries.any { it.event == event }
    }
}
