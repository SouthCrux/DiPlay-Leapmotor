package com.shilapi.xcertplay.network

/** Explicit shell diagnostic repair; never invoked automatically by the app or Bluetooth. */
internal class HotspotIdleBandRepair<C : Any>(private val port: Port<C>) {
    interface Port<C> {
        fun read(): C
        fun idleShutdown(config: C): Boolean
        fun isUserConfiguration(config: C): Boolean
        fun withIdleShutdown(config: C, enabled: Boolean): C
        fun write(config: C): Boolean
    }
    enum class Result { UNCHANGED, APPLIED, REFUSED, REJECTED, VERIFY_FAILED }

    /** Copies the platform's opaque config, never extracting credentials or persisting a parcel. */
    fun set(enabled: Boolean): Result {
        val original = port.read()
        if (port.idleShutdown(original) == enabled) return Result.UNCHANGED
        // Android normalizes this flag on setter calls; refuse a non-restorable config.
        if (!port.isUserConfiguration(original)) return Result.REFUSED
        val next = port.withIdleShutdown(original, enabled)
        if (port.idleShutdown(next) != enabled ||
            port.withIdleShutdown(next, !enabled) != original) return Result.REFUSED
        // Refuse a concurrent Settings change before committing the prepared copy.
        if (port.read() != original) return Result.REFUSED
        if (!port.write(next)) return Result.REJECTED
        return if (port.read() == next) Result.APPLIED else Result.VERIFY_FAILED
    }
}
