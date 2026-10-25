package com.boyoffi9.matrixrainview.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.boyoffi9.matrixrainview.MatrixRainView

/**
 * Compose wrapper for [MatrixRainView]. Parameters are applied to the same
 * underlying view in place, so recomposition doesn't restart the animation.
 */
@Composable
fun MatrixRain(
    modifier: Modifier = Modifier.fillMaxSize(),
    rainColor: Color = Color(0xFF00FF41),
    speed: Float = 1f,
    density: Float = 1f,
    glowEnabled: Boolean = true,
    fadeStrength: Int = 32,
    textSizePx: Float? = null,
    charSet: MatrixRainView.CharSet = MatrixRainView.CharSet.KATAKANA,
    customChars: String? = null,
    touchEffects: Int = 0,
    touchRadiusPx: Float? = null,
    touchPush: Float = 1f,
    glitch: Float = 0f,
    transparent: Boolean = false,
) {
    AndroidView(
        modifier = modifier,
        factory = { context -> MatrixRainView(context) },
        update = { view ->
            view.rainColor = rainColor.toArgb()
            view.speed = speed
            view.density = density
            view.glowEnabled = glowEnabled
            view.fadeStrength = fadeStrength
            textSizePx?.let { view.textSizePx = it }
            view.customChars = customChars
            view.charSet = charSet
            view.touchEffects = touchEffects
            touchRadiusPx?.let { view.touchRadiusPx = it }
            view.touchPush = touchPush
            view.glitchIntensity = glitch
            view.transparentBackground = transparent
        }
    )
}
