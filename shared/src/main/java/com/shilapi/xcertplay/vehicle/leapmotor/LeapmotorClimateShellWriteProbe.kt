package com.shilapi.xcertplay.vehicle.leapmotor

import java.io.Closeable
import kotlin.math.abs

/** Manual diagnostic for SET independently of the known placeholder GET. Never an Ultra backend. */
object LeapmotorClimateShellWriteProbe {
    const val PREFIX = "DIPLAY_CLIMATE_SET_V1"
    data class Request(val areaId: Int, val baselineCelsius: Int, val targetCelsius: Int)
    interface Port : Closeable {
        fun permitted(): Boolean
        fun configurations(): List<LeapmotorClimateConfiguration>
        fun write(areaId: Int, targetCelsius: Float)
        /** Diagnostic errors only; null means this port has no armed observer. No readback. */
        fun observeSetError(): LeapmotorClimateSetErrorObservation.Result? = null
    }

    /** Baseline/side calibration is an external physical prerequisite, not established by parsing. */
    fun parse(args: Array<String>): Pair<Request, Boolean>? {
        if (args.size != 4 || args[0] !in listOf("check", "set-once")) return null
        val values = args.drop(1).map { it.toIntOrNull() ?: return null }
        val request = Request(values[0], values[1], values[2])
        return if (valid(request)) request to (args[0] == "set-once") else null
    }

    private fun valid(request: Request): Boolean =
        LeapmotorClimateSide.entries.any { it.areaId == request.areaId } &&
            request.baselineCelsius in 18..32 && request.targetCelsius in 18..32 &&
            abs(request.targetCelsius - request.baselineCelsius) == 1

    /** At most one setter call. No GET, retry, optimistic cache update or confirmation is possible. */
    fun run(uid: Int, request: Request, submit: Boolean, elapsedNanos: () -> Long,
            onDispatch: (String) -> Unit = {},
            open: () -> Port): List<String> {
        if (uid != 2000) return listOf("$PREFIX|IDENTITY_REJECTED")
        if (!valid(request)) return listOf("$PREFIX|INVALID_ARGUMENTS")
        val lines = mutableListOf("$PREFIX|IDENTITY|uid=2000")
        var port: Port? = null
        var stage = "OPEN"
        var attempted = false
        try {
            port = open()
            stage = "PERMISSION"
            if (!port.permitted()) return lines.apply { add("$PREFIX|ACCESS_DENIED|stage=$stage") }
            stage = "CONFIG"
            val configs = port.configurations().filter {
                it.propertyId == LeapmotorClimateController.TEMPERATURE_PROPERTY_ID
            }
            val config = configs.singleOrNull()
            val area = config?.areas?.filter { it.areaId == request.areaId }?.singleOrNull()
            val usable = config != null && config.access == 3 &&
                config.type == java.lang.Float::class.java && config.changeMode == 1 &&
                area != null && area.minCelsius.isFinite() && area.maxCelsius.isFinite() &&
                area.minCelsius < area.maxCelsius &&
                request.baselineCelsius.toFloat() in area.minCelsius..area.maxCelsius &&
                request.targetCelsius.toFloat() in area.minCelsius..area.maxCelsius
            if (!usable) return lines.apply { add("$PREFIX|CONFIG_REJECTED") }
            stage = "PERMISSION"
            if (!port.permitted()) return lines.apply { add("$PREFIX|ACCESS_DENIED|stage=$stage") }
            lines += "$PREFIX|REQUEST|area=${request.areaId}|baseline=${request.baselineCelsius}|target=${request.targetCelsius}|calibration=EXTERNAL_REQUIRED"
            if (!submit) return lines.apply { add("$PREFIX|CHECK_ONLY|setterInvoked=false") }
            stage = "SET"
            val at = elapsedNanos()
            if (at < 0) return lines.apply { add("$PREFIX|CLOCK_REJECTED") }
            val dispatch = "$PREFIX|DISPATCH_BEGIN|area=${request.areaId}|target=${request.targetCelsius}|submittedAtNanos=$at|physicalResult=UNKNOWN"
            lines += dispatch
            onDispatch(dispatch)
            attempted = true
            port.write(request.areaId, request.targetCelsius.toFloat())
            lines += "$PREFIX|SUBMITTED_UNCONFIRMED|submittedAtNanos=$at"
            stage = "PERMISSION"
            if (!port.permitted()) lines += "$PREFIX|ACCESS_DENIED|stage=$stage"
        } catch (_: SecurityException) {
            lines += "$PREFIX|ACCESS_DENIED|stage=$stage"
        } catch (_: Exception) {
            lines += "$PREFIX|UNAVAILABLE|stage=$stage"
        } finally {
            if (attempted) {
                try {
                    port?.observeSetError()?.let { observation ->
                        lines += if (observation.state == LeapmotorClimateSetErrorObservation.State.ERROR_OBSERVED) {
                            "$PREFIX|VHAL_ERROR_OBSERVED|area=${request.areaId}|code=${observation.error ?: LeapmotorClimateSetErrorObservation.Error.UNKNOWN}|physicalResult=UNKNOWN"
                        } else {
                            "$PREFIX|ASYNC_ERROR_WINDOW|state=${observation.state.name}|physicalResult=UNKNOWN"
                        }
                    }
                } catch (_: Exception) {
                    lines += "$PREFIX|UNAVAILABLE|stage=ERROR_OBSERVATION"
                }
            }
            val closed = runCatching { port?.close() }.isSuccess
            lines += "$PREFIX|END|setterAttempted=$attempted|closed=$closed|physicalResult=UNKNOWN"
        }
        return lines
    }

    /** Zero means the diagnostic check/send returned, never that a physical command succeeded. */
    fun exitCode(lines: List<String>): Int = if (
        lines.any { it.contains("|CHECK_ONLY|") || it.contains("|SUBMITTED_UNCONFIRMED|") } &&
        lines.none { it.contains("|ACCESS_DENIED|") || it.contains("|UNAVAILABLE|") ||
            it.contains("|VHAL_ERROR_OBSERVED|") || it.contains("|ASYNC_ERROR_WINDOW|state=INTERRUPTED|") }
    ) 0 else 2
}
