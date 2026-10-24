// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

/**
 * Confirmed Apple resource URLs, not a claim that the iPhone accepted a widget or its data service.
 * Provenance: reference asset 44C9FA31EC/v985, Enums.lua ContentURL.
 * There is deliberately no guessed climate URL. Temperature controls use the climate accessory.
 */
internal enum class UltraWidget(val contentUrl: String) {
    CHARGING("widget://?identifier=com.apple.charging"),
    TIRES("widget://?identifier=com.apple.tires"),
    CAR_BODY_STATUS("widget://?identifier=com.apple.carBodyStatus"),
}

/** A destination must correspond to a currently installed layout and an implemented region renderer. */
internal data class UltraWidgetZone(val panelIdentifier: String, val zoneIdentifier: String) {
    init {
        require(panelIdentifier.matches(Regex("[A-Za-z0-9_.-]{1,128}")))
        require(zoneIdentifier == "zone1" || zoneIdentifier == "zone2")
    }
}

/** Independent proofs: a reading alone does not prove the phone-facing vehicle schema exists. */
internal data class UltraWidgetVehicleEvidence(
    val widget: UltraWidget,
    val observedAtMillis: Long,
    val validUntilMillis: Long,
    val phoneVehicleSchemaVerified: Boolean,
)

/**
 * Selection contract only: this object neither advertises an accessory nor sends a request.
 * The currently implemented Wallpaper center has no instrument zones; its default is empty.
 * A future owner must remove its zones on layout/surface retirement and re-evaluate before sending.
 */
internal class UltraWidgetPolicy(implementedZones: Set<UltraWidgetZone> = emptySet()) {
    private val zones = implementedZones.toSet()
    enum class Blocked { NO_IMPLEMENTED_ZONE, VEHICLE_SCHEMA_UNVERIFIED, NO_FRESH_VEHICLE_DATA }
    sealed interface Decision {
        data class Rejected(val reason: Blocked) : Decision
        /** A request candidate, not confirmation that remote content exists or has been displayed. */
        data class Candidate(val request: UltraWidgetRequest) : Decision
    }

    fun select(widget: UltraWidget, zone: UltraWidgetZone,
        evidence: UltraWidgetVehicleEvidence?, nowMillis: Long): Decision {
        if (zone !in zones) return Decision.Rejected(Blocked.NO_IMPLEMENTED_ZONE)
        if (evidence == null || evidence.widget != widget || !evidence.phoneVehicleSchemaVerified)
            return Decision.Rejected(Blocked.VEHICLE_SCHEMA_UNVERIFIED)
        if (nowMillis < 0 || evidence.observedAtMillis < 0 || evidence.observedAtMillis > nowMillis ||
            evidence.validUntilMillis <= evidence.observedAtMillis || nowMillis >= evidence.validUntilMillis)
            return Decision.Rejected(Blocked.NO_FRESH_VEHICLE_DATA)
        return Decision.Candidate(UltraWidgetRequest(widget, zone))
    }
}

/**
 * LIVI 4bcd1335 vehicle_state.rs: cluster_content/zone_service.
 * Instance IDs must be assigned by the eventual session owner; they are not hardware property IDs.
 * A sender still needs a live protocol channel, destination ownership and response handling.
 */
internal data class UltraWidgetRequest(val widget: UltraWidget, val zone: UltraWidgetZone) {
    companion object {
        const val ACCESSORY_TYPE = 0x01200001L
        const val REQUEST_CONTENT_SERVICE_TYPE = 0x17000004L
        const val CONTENT_URL_TYPE = 0x30000007L
        const val ON_TYPE = 0x30000002L
        const val USER_DISMISSIBLE_TYPE = 0x36000063L
        const val DISPLAY_PANEL_IDENTIFIER_TYPE = 0x37000007L
        const val DISPLAY_ZONE_IDENTIFIER_TYPE = 0x3700000BL
    }
}
