package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Fixed App Store bundle identifiers. App launch is experimental, not voice-mode confirmation. */
enum class CarPlayAssistantApp(val bundleId: String) {
    CHATGPT("com.openai.chat"),
    GEMINI("com.google.gemini"),
    CLAUDE("com.anthropic.claude"),
    ;

    fun launchRequest(): Iap2Frame = Iap2Messages.buildRaw(0xEA02) {
        string(0, bundleId)
    }
}
