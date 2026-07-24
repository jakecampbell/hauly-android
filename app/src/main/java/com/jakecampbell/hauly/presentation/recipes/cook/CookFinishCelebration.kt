package com.jakecampbell.hauly.presentation.recipes.cook

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.jakecampbell.hauly.R
import com.jakecampbell.hauly.presentation.theme.CookMagenta
import com.jakecampbell.hauly.presentation.theme.HaulyBlue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

/** How long the send-off plays. Brisk, but long enough to read the flip + arc. */
private const val EnjoyMillis = 1600

/** The moment the pan snaps forward and lets go of the word (progress 0..1). */
private const val ReleaseAt = 0.30f

/** A droplet flicked up off the pan on the flip. */
private data class Droplet(
    val xOffset: Float, // dp from centre, across the pan's width
    val rise: Float,    // dp it climbs before gravity wins
    val radius: Float,  // dp
    val startAt: Float, // progress at which it leaves the pan
    val drift: Float,   // dp horizontal drift over its life
)

/** A fresh scatter of blue droplets, regenerated per celebration. */
private fun newDroplets(): List<Droplet> = List(9) {
    Droplet(
        xOffset = -24f + Random.nextFloat() * 48f,
        rise = 80f + Random.nextFloat() * 80f,
        radius = 2.5f + Random.nextFloat() * 2.5f,
        // Clustered around the flip so they spray as the pan snaps forward.
        startAt = ReleaseAt - 0.06f + Random.nextFloat() * 0.12f,
        drift = -16f + Random.nextFloat() * 32f,
    )
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * The pan's tilt over the toss, in degrees: a small wind-up back, a fast snap
 * forward (the flip that launches the word at [ReleaseAt]), then a damped wobble
 * as it "catches".
 */
private fun panFlip(p: Float): Float = when {
    p < 0.15f -> lerp(0f, -20f, p / 0.15f)
    p < ReleaseAt -> lerp(-20f, 32f, (p - 0.15f) / (ReleaseAt - 0.15f))
    p < 0.72f -> {
        val t = (p - ReleaseAt) / (0.72f - ReleaseAt)
        32f * (1f - t) * cos(t * PI.toFloat() * 2.5f)
    }
    else -> 0f
}

/** The pan's vertical bob, in dp (positive = down): dip, thrust up, settle. */
private fun panBob(p: Float): Float = when {
    p < 0.15f -> lerp(0f, 6f, p / 0.15f)
    p < ReleaseAt -> lerp(6f, -22f, (p - 0.15f) / (ReleaseAt - 0.15f))
    p < 0.6f -> lerp(-22f, 0f, (p - ReleaseAt) / (0.6f - ReleaseAt))
    else -> 0f
}

/**
 * A one-shot send-off played centre-screen when the user closes cook mode (R8.22):
 * a magenta frying pan winds up and **flips the word "enjoy" into the air**, with a
 * spray of small hauly-blue droplets flicked up off the pan on the flip. The word and
 * pan are the cook-mode magenta; the droplets the brand blue.
 *
 * Driven by [trigger] like [com.jakecampbell.hauly.presentation.shopping.HaulCelebration]:
 * each increment restarts the animation. Idle (trigger 0, or once finished) it draws
 * nothing and — being a plain, non-clickable overlay — never intercepts touches, so
 * the screen underneath stays fully interactive.
 */
@Composable
fun CookFinishCelebration(trigger: Int, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    // Regenerated each run so no two flips spray the droplets the same way.
    val droplets = remember(trigger) { newDroplets() }

    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = EnjoyMillis, easing = LinearEasing))
    }

    val p = progress.value
    // Nothing to draw before the first trigger or after the toss has faded.
    if (trigger == 0 || p >= 1f) return

    val flip = panFlip(p)
    val bob = panBob(p)

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The pan sits just below centre, winds up and flips.
        Icon(
            painter = painterResource(R.drawable.ic_frying_pan),
            contentDescription = null,
            tint = CookMagenta,
            modifier = Modifier
                .size(88.dp)
                .graphicsLayer {
                    translationY = (40f + bob).dp.toPx()
                    rotationZ = flip
                },
        )

        // Blue droplets flicked up off the pan on the flip, arcing up and fading.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val originX = size.width / 2f
            val originY = size.height / 2f + 30.dp.toPx() // the pan's lip
            droplets.forEach { d ->
                val life = ((p - d.startAt) / 0.5f).coerceIn(0f, 1f)
                if (life <= 0f || life >= 1f) return@forEach
                val climb = 1f - (1f - life) * (1f - life)          // decelerating rise
                val gravity = 0.35f * life * life                    // a little droop back down
                val y = originY - climb * d.rise.dp.toPx() + gravity * d.rise.dp.toPx()
                val x = originX + d.xOffset.dp.toPx() + d.drift.dp.toPx() * life
                drawCircle(
                    color = HaulyBlue.copy(alpha = (1f - life).coerceIn(0f, 1f)),
                    radius = d.radius.dp.toPx() * (1f - 0.3f * life),
                    center = Offset(x, y),
                )
            }
        }

        // "enjoy" rests in the pan through the wind-up, then is flung up on the flip:
        // it arcs up (ease-out), grows, holds, and fades.
        val launch = ((p - ReleaseAt) / 0.42f).coerceIn(0f, 1f)
        val rise = 1f - (1f - launch) * (1f - launch)
        // While still seated it rides the pan's bob; that release fades in as it flies.
        val seatedFollow = bob * (1f - launch)
        val scale = 0.5f + 0.55f * rise - 0.05f * (rise * rise)
        val spin = lerp(flip * 0.35f, 0f, rise)              // leaves the pan spinning, straightens
        val alpha = when {
            p < 0.1f -> p / 0.1f
            p < 0.8f -> 1f
            else -> ((1f - p) / 0.2f).coerceIn(0f, 1f)
        }
        Text(
            text = "enjoy",
            color = CookMagenta,
            fontSize = 46.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.graphicsLayer {
                translationY = (18f + seatedFollow - rise * 155f).dp.toPx()
                scaleX = scale
                scaleY = scale
                rotationZ = spin
                this.alpha = alpha
            },
        )
    }
}
