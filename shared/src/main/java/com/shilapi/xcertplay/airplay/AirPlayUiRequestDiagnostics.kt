package com.shilapi.xcertplay.airplay

/** Metadata only. Arbitrary URLs may contain personal data and never enter this report. */
internal object AirPlayUiRequestDiagnostics {
    fun summary(params: Map<String, Any?>): String {
        val url = params["url"]
        val target = when {
            !params.containsKey("url") || url == "" -> "DEFAULT"
            url !is String -> "INVALID"
            url == VideoInCar.UI_URL -> "VIDEO_PLAYBACK"
            url == CarPlayClusterDisplay.MAP_URL -> "MAP_CLUSTER"
            else -> "OTHER"
        }
        return "airplay UI request target=$target"
    }
}
