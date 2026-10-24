package com.shilapi.xcertplay.vehicle.leapmotor

import java.io.Closeable

/** Fixed read-only diagnostic contract. No setter, command target or Ultra capability is exposed. */
object LeapmotorClimateShellProbe {
    const val PREFIX = "DIPLAY_CLIMATE_READ_V1"
    const val PROPERTY = LeapmotorClimateController.TEMPERATURE_PROPERTY_ID
    interface Port : Closeable {
        fun permitted(): Boolean
        fun configurations(): List<LeapmotorClimateConfiguration>
        fun read(area: Int): CarPropertyReading?
    }

    fun inspect(uid: Int, elapsedNanos: () -> Long, open: () -> Port): List<String> {
        if (uid != 2000) return listOf("$PREFIX|IDENTITY_REJECTED|uid=$uid")
        val lines = mutableListOf("$PREFIX|IDENTITY|uid=2000")
        var port: Port? = null
        var stage = "OPEN"
        try {
            port = open()
            val before = port.permitted()
            lines += "$PREFIX|PERMISSION|before=$before"
            if (!before) return lines
            stage = "CONFIG"
            val configs = port.configurations().filter { it.propertyId == PROPERTY }
            if (!port.permitted()) {
                lines += "$PREFIX|PERMISSION_REVOKED"
                return lines
            }
            if (configs.size != 1) {
                lines += "$PREFIX|CONFIG_UNAVAILABLE|count=${configs.size}"
                return lines
            }
            val config = configs.single()
            val floatType = config.type == java.lang.Float::class.java
            lines += "$PREFIX|CONFIG|id=$PROPERTY|access=${config.access}|changeMode=${config.changeMode}|floatType=$floatType|stepKnown=false"
            for (side in LeapmotorClimateSide.entries) {
                val area = config.areas.filter { it.areaId == side.areaId }.singleOrNull()
                val valid = area != null && area.minCelsius.isFinite() && area.maxCelsius.isFinite() &&
                    area.minCelsius < area.maxCelsius && floatType && config.access in listOf(1, 3) && config.changeMode == 1
                lines += "$PREFIX|AREA|area=${side.areaId}|valid=$valid" +
                    "|min=${area?.minCelsius?.takeIf { it.isFinite() } ?: "UNKNOWN"}" +
                    "|max=${area?.maxCelsius?.takeIf { it.isFinite() } ?: "UNKNOWN"}"
                if (!valid) continue
                stage = "READ"
                if (!port.permitted()) { lines += "$PREFIX|PERMISSION_REVOKED"; return lines }
                val raw = port.read(side.areaId)
                val now = elapsedNanos()
                if (!port.permitted()) { lines += "$PREFIX|PERMISSION_REVOKED"; return lines }
                if (raw == null) {
                    lines += "$PREFIX|READ_UNAVAILABLE|area=${side.areaId}"
                    continue
                }
                val value = (raw.value as? Float)?.takeIf { it.isFinite() }
                val timestamp = raw.timestampNanos
                val age = timestamp.takeIf { it > 0 && it <= now }?.let { (now - it) / 1_000_000 }
                val timeState = when {
                    timestamp < 0 || timestamp > now -> "INVALID"
                    timestamp == 0L -> "ZERO"
                    age != null && age > 5_000 -> "OLD_ON_CHANGE"
                    else -> "RECENT"
                }
                val identityMatches = raw.id == PROPERTY && raw.areaId == side.areaId
                val inHalRange = value != null && value >= area!!.minCelsius && value <= area.maxCelsius
                lines += "$PREFIX|READ|requestedArea=${side.areaId}|id=${raw.id}|area=${raw.areaId}|status=${raw.status}" +
                    "|floatType=${raw.value is Float}|value=${value ?: "UNKNOWN"}|identityMatches=$identityMatches" +
                    "|inHalRange=$inHalRange|inOemUiRange=${value != null && value in 18f..32f}" +
                    "|oemTimestampNanos=$timestamp|elapsedNanos=$now|receivedAgeMs=${age ?: "UNKNOWN"}|time=$timeState"
            }
        } catch (_: SecurityException) {
            lines += "$PREFIX|ACCESS_DENIED|stage=$stage"
        } catch (_: Throwable) {
            lines += "$PREFIX|UNAVAILABLE|stage=$stage"
        } finally {
            lines += "$PREFIX|PERMISSION|after=${port?.let { runCatching { it.permitted() }.getOrNull() } ?: "UNKNOWN"}"
            val closed = runCatching { port?.close() }.isSuccess
            lines += "$PREFIX|END|closed=$closed"
        }
        return lines
    }
}
