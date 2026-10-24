package com.shilapi.xcertplay.transport

/** A real catalog resource descriptor, not an installed theme or a rendering capability. */
data class Iap2ThemeAsset(val identifier: String, val version: Long, val sdkVersion: String) {
    init {
        require(identifier.matches(Regex("[A-Za-z0-9_.-]{1,128}")))
        require(version in 0..0xffff_ffffL)
        require(sdkVersion.length <= 32 && sdkVersion.matches(Regex("[0-9]+(\\.[0-9]+){0,3}")))
    }
}
