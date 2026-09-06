package com.boyoffi9.matrixrainview.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.boyoffi9.matrixrainview.MatrixRainView

/**
 * Compose entry point for [MatrixRainView].
 *
 * ```kotlin
 * Box(Modifier.fillMaxSize()) {
 *     MatrixRain(modifier = Modifier.fillMaxSize())
 *     // your real UI on top
 * }
 * ```
 *
 * Recomposition updates the underlying [MatrixRainView]'s properties in
 * place — the view itself is only created once per composition, so the
 * animation isn't restarted on every parameter change.
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
        }
    )
}
