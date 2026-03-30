package com.nuvio.tv.ui.screens.player

import androidx.annotation.StringRes
import androidx.media3.ui.PlayerView
import com.nuvio.tv.R

enum class AspectMode(@StringRes val labelResId: Int) {
    ORIGINAL(R.string.player_aspect_fit),
    FULL_SCREEN(R.string.player_aspect_crop),
    SLIGHT_ZOOM(R.string.player_aspect_mode_slight_zoom),
    CINEMA_ZOOM(R.string.player_aspect_mode_cinema_zoom),
    VERTICAL_STRETCH(R.string.player_aspect_fit_height),
    HORIZONTAL_STRETCH(R.string.player_aspect_fit_width)
}

internal fun nextAspectMode(current: AspectMode): AspectMode {
    val modes = AspectMode.entries
    val nextIndex = (modes.indexOf(current) + 1) % modes.size
    return modes[nextIndex]
}

internal fun aspectModeLabel(mode: AspectMode, getString: (Int) -> String): String =
    getString(mode.labelResId)

internal fun applyAspectMode(playerView: PlayerView, mode: AspectMode) {
    when (mode) {
        AspectMode.ORIGINAL -> {
            playerView.scaleX = 1.0f
            playerView.scaleY = 1.0f
        }

        AspectMode.FULL_SCREEN -> applyCoverAspectScale(playerView)

        AspectMode.SLIGHT_ZOOM -> {
            playerView.scaleX = 1.15f
            playerView.scaleY = 1.15f
        }

        AspectMode.CINEMA_ZOOM -> {
            playerView.scaleX = 1.33f
            playerView.scaleY = 1.33f
        }

        AspectMode.VERTICAL_STRETCH -> {
            playerView.scaleX = 1.0f
            playerView.scaleY = 1.33f
        }

        AspectMode.HORIZONTAL_STRETCH -> {
            playerView.scaleX = 1.3333f
            playerView.scaleY = 1.0f
        }
    }
}

private fun applyCoverAspectScale(playerView: PlayerView) {
    val videoSize = playerView.player?.videoSize
    val videoAspect = if ((videoSize?.height ?: 0) > 0) {
        ((videoSize?.width ?: 0).toFloat() * (videoSize?.pixelWidthHeightRatio ?: 1f)) /
            videoSize!!.height.toFloat()
    } else {
        0f
    }

    val viewAspect = if (playerView.width > 0 && playerView.height > 0) {
        playerView.width.toFloat() / playerView.height.toFloat()
    } else {
        0f
    }

    if (videoAspect > 0f && viewAspect > 0f) {
        if (videoAspect > viewAspect) {
            playerView.scaleX = 1.0f
            playerView.scaleY = videoAspect / viewAspect
        } else {
            playerView.scaleX = viewAspect / videoAspect
            playerView.scaleY = 1.0f
        }
    } else {
        playerView.scaleX = 1.0f
        playerView.scaleY = 1.0f
    }
}
