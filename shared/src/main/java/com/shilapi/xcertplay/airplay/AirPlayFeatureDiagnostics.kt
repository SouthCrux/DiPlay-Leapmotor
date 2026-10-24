package com.shilapi.xcertplay.airplay

/** Observes negotiation without exporting arbitrary peer values or enabling any capability. */
internal object AirPlayFeatureDiagnostics {
    // The final three tokens are research candidates. Recognizing them here only records proposals.
    private val knownFeatures = listOf(
        "hevc", "iAPChannel", "viewAreas", "altScreen", "cornerMasks",
        VideoInCar.FEATURE, MAIN_BUFFERED_FEATURE,
        "uiSync", "vehicleStateProtocol", "fileTransfer",
    )
    private val knownFeatureSet = knownFeatures.toSet()

    fun summary(proposed: Any?, enabled: List<*>): String {
        val proposedState = when (proposed) {
            null -> "absent"
            is List<*> -> "list"
            else -> "malformed"
        }
        val proposal = inspect(proposed as? List<*> ?: emptyList<Any?>())
        val selection = inspect(enabled)
        return "airplay features proposedState=$proposedState " +
            "proposed=${proposal.features} proposedUnknown=${proposal.unknown} proposedInvalid=${proposal.invalid} " +
            "enabled=${selection.features} enabledUnknown=${selection.unknown} enabledInvalid=${selection.invalid}"
    }

    private fun inspect(values: List<*>): FeatureSummary {
        val recognized = HashSet<String>()
        var unknown = 0
        var invalid = 0
        for (value in values) {
            when {
                value !is String -> invalid++
                value in knownFeatureSet -> recognized += value
                else -> unknown++
            }
        }
        return FeatureSummary(
            features = knownFeatures.filter { it in recognized }.joinToString(prefix = "[", postfix = "]", separator = ","),
            unknown = unknown,
            invalid = invalid,
        )
    }

    private data class FeatureSummary(val features: String, val unknown: Int, val invalid: Int)
}
