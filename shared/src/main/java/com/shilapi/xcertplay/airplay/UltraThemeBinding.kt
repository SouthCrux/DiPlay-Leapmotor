package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.transport.Iap2ThemeAsset
import kotlin.math.roundToInt

/** Logical resource geometry, not the car's physical display identity or a renderer. */
data class UltraThemeDisplay(val id: String, val width: Int, val height: Int, val densityDpi: Int) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_.-]{1,128}")))
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 32_000_000)
        require(densityDpi in 40..1000)
    }

    fun config(fps: Int): AirPlayDisplayConfig = AirPlayDisplayConfig(
        width, height, (width * 25.4 / densityDpi).roundToInt(),
        (height * 25.4 / densityDpi).roundToInt(), fps = fps,
    )
}

/** One atomic resource/stream/panel binding. No live vehicle characteristics or layout claims. */
data class UltraThemeBinding(
    val asset: Iap2ThemeAsset,
    val main: UltraThemeDisplay,
    val cluster: UltraThemeDisplay? = null,
    /** Explicit development composition destination; requires a matching center protocol owner. */
    val composeMainDisplay: Boolean = false,
    /** Independent type-111 navigation video, not a scripted instrument panel or a second theme owner. */
    val conventionalNavigation: AirPlayDisplayConfig? = null,
) {
    init {
        require(main.id != cluster?.id)
        require(cluster == null || conventionalNavigation == null) {
            "The alternate stream cannot be both themed instruments and conventional navigation"
        }
    }

    fun withConventionalNavigation(display: AirPlayDisplayConfig?): UltraThemeBinding =
        copy(conventionalNavigation = display)

    fun configure(config: AirPlayConfig): AirPlayConfig {
        require(config.ultraHandshake)
        return config.copy(main = main.config(config.main.fps),
            cluster = conventionalNavigation ?: cluster?.config(config.main.fps),
            ultraThemeBinding = this)
    }

    internal fun matches(config: AirPlayConfig): Boolean =
        config.main.widthPixels == main.width && config.main.heightPixels == main.height &&
            (if (conventionalNavigation != null) config.cluster == conventionalNavigation
             else if (cluster == null) config.cluster == null else
                config.cluster?.widthPixels == cluster.width && config.cluster.heightPixels == cluster.height)
}

internal val AirPlayConfig.activeThemeBinding: UltraThemeBinding?
    get() = ultraThemeBinding?.takeIf { ultraHandshake }

/** Distinguishes an implemented ordinary navigation decoder from a theme-owned instrument canvas. */
internal val AirPlayConfig.hasConventionalClusterNavigation: Boolean
    get() = activeThemeBinding?.conventionalNavigation != null
