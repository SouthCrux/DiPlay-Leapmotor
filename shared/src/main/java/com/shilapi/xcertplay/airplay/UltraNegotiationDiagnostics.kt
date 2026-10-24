// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

/** Local declaration and negotiated transport facts; none establish resource acceptance. */
internal object UltraNegotiationDiagnostics {
    fun summary(config: AirPlayConfig, uiSync: Boolean): String {
        val theme = config.activeThemeBinding
        return "ultra negotiation: themeDeclared=${theme != null} " +
            "assetVersion=${theme?.asset?.version ?: "none"} " +
            "selectedPanels=${UltraHandshake.panelIds(config).size} " +
            "clusterDeclared=${config.cluster != null} mainCanvas=${config.main.widthPixels}x${config.main.heightPixels} " +
            "uiSyncNegotiated=$uiSync resourceAcceptance=unverified topologyAcceptance=unverified"
    }
}

/** Script-owner observations contain only shape/numeric kinds, never peer identifiers or bodies. */
internal class UiSyncOwnerDiagnostics {
    private data class Shape(val panel: Int, val sent: Boolean, val type: Long, val command: Long?,
        val layoutIdPresent: Boolean, val layoutRelation: String, val settingsStreamAction: String)
    private val seen = mutableSetOf<Shape>()
    private val requestedLayouts = mutableMapOf<Int, String>()

    @Synchronized fun record(panel: Int, sent: Boolean, bytes: ByteArray): String? {
        if (seen.size >= 32 || panel < 0) return null
        val header = BoundedPlist.decode(bytes) ?: return null
        val type = header["type"] as? Long ?: return null
        val command = if (type == 2L) (header["data"] as? ByteArray)?.let(BoundedPlist::decode) else null
        val data = command?.get("data") as? Map<*, *>
        val layoutId = data?.get("layoutID") as? String
        val kind = command?.get("type") as? Long
        // The original controller handles these fields only in START_ZONE_TRANSITION (1).
        // Observe structure, never the input identifier, URL or whether a stream was presented.
        val streamAction = if (kind != 1L || data == null) "NONE" else {
            val present = data.containsKey("presentStream")
            val dismiss = data.containsKey("dismissStream")
            when {
                present && dismiss -> "AMBIGUOUS"
                !present && !dismiss -> "NONE"
                else -> {
                    val value = data[if (present) "presentStream" else "dismissStream"]
                    if (value !is String || !value.matches(Regex("[A-Za-z0-9_.-]{1,128}"))) "INVALID"
                    else if (present) "PRESENT" else "DISMISS"
                }
            }
        }
        val validId = layoutId?.takeIf { it.matches(Regex("[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}")) }
        val relation = when {
            sent && kind == 5L && validId != null -> {
                requestedLayouts[panel] = validId.lowercase(); "requested"
            }
            !sent && kind in listOf(3L, 6L) && validId != null -> requestedLayouts[panel]?.let {
                if (it == validId.lowercase()) "matchesRequest" else "differsFromRequest"
            } ?: "noRequestObserved"
            else -> "unavailable"
        }
        val shape = Shape(panel, sent, type, kind, layoutId != null, relation, streamAction)
        if (!seen.add(shape)) return null
        return "ultra handshake: script observation panelIndex=$panel direction=${if (sent) "sent" else "received"} " +
            "type=$type command=${shape.command ?: "none"} layoutIdPresent=${shape.layoutIdPresent} " +
            "layoutRelation=$relation settingsStreamAction=$streamAction " +
            "resourceAcceptance=unverified" + if (seen.size == 32) " observationLimitReached=true" else ""
    }
}
