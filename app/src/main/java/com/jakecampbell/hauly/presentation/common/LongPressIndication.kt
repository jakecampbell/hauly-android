package com.jakecampbell.hauly.presentation.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.hypot

/**
 * Draws a growing color "iris" out of the touch point while a press is held,
 * sized so it reaches full coverage right as the long-press action fires —
 * a visual heads-up that holding will trigger something. Releasing early
 * fades it away. Pass the same [interactionSource] to the clickable so the
 * iris tracks the actual press.
 */
@Composable
fun Modifier.longPressIris(interactionSource: InteractionSource): Modifier {
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val progress = remember { Animatable(0f) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(interactionSource) {
        // collectLatest: a release/cancel interrupts the in-flight grow.
        interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    origin = interaction.pressPosition
                    progress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(longPressTimeout.toInt(), easing = LinearEasing),
                    )
                    // Held to completion: the action fired; let the iris fade.
                    progress.animateTo(0f, tween(200))
                }

                is PressInteraction.Release, is PressInteraction.Cancel ->
                    progress.animateTo(0f, tween(120))
            }
        }
    }

    return drawBehind {
        if (progress.value > 0f) {
            drawCircle(
                color = color,
                radius = progress.value * hypot(size.width, size.height),
                center = origin,
            )
        }
    }
}
