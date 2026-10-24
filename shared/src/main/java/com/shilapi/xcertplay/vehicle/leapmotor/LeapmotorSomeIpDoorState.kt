package com.shilapi.xcertplay.vehicle.leapmotor

/** Passive observations only. Raw door/tailgate codes do not establish open, closed or locked.
 * Front channels retain OEM driver/passenger identities without assuming a physical side.
 */
class LeapmotorSomeIpDoorState(elapsedMillis: () -> Long) {
    enum class Channel(val event: Int) {
        DRIVER_DOOR(LeapmotorPreparedModules.Signal.DRIVER_DOOR.event),
        PASSENGER_DOOR(LeapmotorPreparedModules.Signal.PASSENGER_DOOR.event),
        REAR_LEFT_DOOR(LeapmotorPreparedModules.Signal.REAR_LEFT_DOOR.event),
        REAR_RIGHT_DOOR(LeapmotorPreparedModules.Signal.REAR_RIGHT_DOOR.event),
        TAILGATE_STATUS(LeapmotorPreparedModules.Signal.TAILGATE_STATUS.event),
        TAILGATE_POSITION(LeapmotorPreparedModules.Signal.TAILGATE_POSITION.event),
        POWER_TAILGATE_STATUS(LeapmotorPreparedModules.Signal.POWER_TAILGATE_STATUS.event),
        HOOD_STATUS(LeapmotorPreparedModules.Signal.HOOD_STATUS.event),
        VEHICLE_LOCK_STATUS(LeapmotorPreparedModules.Signal.VEHICLE_LOCK_STATUS.event),
        REAR_LEFT_CHILD_LOCK_STATUS(LeapmotorPreparedModules.Signal.REAR_LEFT_CHILD_LOCK_STATUS.event),
        REAR_RIGHT_CHILD_LOCK_STATUS(LeapmotorPreparedModules.Signal.REAR_RIGHT_CHILD_LOCK_STATUS.event),
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

    fun readings(): List<Reading> = state.readings(LeapmotorPreparedModules.Module.DOORS_LOCKS).map {
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
