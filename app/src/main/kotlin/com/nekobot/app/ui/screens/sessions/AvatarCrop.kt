package com.nekobot.app.ui.screens.sessions

import kotlin.math.min
import kotlin.math.roundToInt

internal data class AvatarCrop(val left: Int, val top: Int, val side: Int)

/** Normalized pan/zoom keeps the visible square and the persisted crop identical. */
internal fun avatarCrop(width: Int, height: Int, zoom: Float, horizontal: Float, vertical: Float): AvatarCrop {
    require(width > 0 && height > 0)
    val side = (min(width, height) / zoom.coerceIn(1f, 4f)).roundToInt().coerceIn(1, min(width, height))
    return AvatarCrop(
        ((width - side) * horizontal.coerceIn(0f, 1f)).roundToInt(),
        ((height - side) * vertical.coerceIn(0f, 1f)).roundToInt(),
        side
    )
}
