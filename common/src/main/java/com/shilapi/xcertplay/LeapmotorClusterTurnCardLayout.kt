package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay

/** The C11 window is already cropped to its unobstructed navigation tile, unlike a BYD panel. */
internal object LeapmotorClusterTurnCardLayout {
    /** Keep the central map unobstructed: maneuver at the top, route totals at the bottom. */
    fun verticalInset(height: Int): Int = (height * 0.15f).toInt()

    fun rightInset(width: Int): Int = (width * 0.02f).toInt()

    fun navigationCard(width: Int, height: Int, sizePercent: Int): ClusterTurnCardOverlay.CardRect {
        val card = card(width, height, 50, 12, sizePercent)
        return card.copy(left = (width - rightInset(width) - card.width).coerceAtLeast(0), top = verticalInset(height).coerceAtMost(height - card.height))
    }

    fun card(width: Int, height: Int, xPercent: Int, yPercent: Int, sizePercent: Int): ClusterTurnCardOverlay.CardRect {
        require(width > 0 && height > 0)
        val size = ClusterTurnCardOverlay.snap(sizePercent, ClusterTurnCardOverlay.sizePercents)
        val cardWidth = (width * size / 100).coerceAtLeast(150).coerceAtMost(width)
        val cardHeight = (cardWidth / 2.64f).toInt().coerceAtLeast(58).coerceAtMost(height)
        val x = ClusterTurnCardOverlay.snap(xPercent, ClusterTurnCardOverlay.xPercents)
        val y = ClusterTurnCardOverlay.snap(yPercent, ClusterTurnCardOverlay.yPercents)
        val left = (width * x / 100 - cardWidth / 2).coerceIn(0, width - cardWidth)
        val top = (height * y / 100 - cardHeight / 2).coerceIn(0, height - cardHeight)
        return ClusterTurnCardOverlay.CardRect(left, top, cardWidth, cardHeight)
    }
}
