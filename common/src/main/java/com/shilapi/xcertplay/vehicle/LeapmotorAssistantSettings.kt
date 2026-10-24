package com.shilapi.xcertplay.vehicle

import android.content.Context
import com.shilapi.xcertplay.transport.CarPlayAssistantApp

internal object LeapmotorAssistantSettings {
    private const val PREFS = "diplay_c11_voice_button"
    enum class Action(val app: CarPlayAssistantApp? = null) {
        SYSTEM, NONE, CHATGPT(CarPlayAssistantApp.CHATGPT), GEMINI(CarPlayAssistantApp.GEMINI),
        CLAUDE(CarPlayAssistantApp.CLAUDE),
    }

    fun enabled(context: Context): Boolean = VehicleIntegrationPolicy.isLeapmotorC11(context) &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
    }

    fun action(context: Context, double: Boolean): Action {
        val fallback = if (double) Action.CHATGPT else Action.SYSTEM
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(if (double) "double" else "single", fallback.name)
        // Corrupt or newer unsupported actions must not launch an unrelated assistant.
        return Action.entries.firstOrNull { it.name == saved } ?: Action.NONE
    }

    fun setAction(context: Context, double: Boolean, action: Action) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(if (double) "double" else "single", action.name).apply()
    }
}
