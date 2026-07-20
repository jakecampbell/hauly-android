package com.jakecampbell.hauly.presentation.shopping

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Dot/line palette for the burst (R7.25). */
private val HaulColors = listOf(
    Color(0xFFFFCA3A),
    Color(0xFF06AFFF),
    Color(0xFFFF06AF),
    Color(0xFF5D2E8C),
)

/** One radiating particle: a dot, or a short line drawn along its own heading. */
private data class Particle(
    val angle: Float,      // radians, direction from center
    val distance: Float,   // 0..1 fraction of the burst radius it flies to
    val color: Color,
    val isLine: Boolean,
    val thickness: Float,  // dot radius / line stroke width, in dp
    val length: Float,     // line tail length as a fraction of the radius
    val fadeStart: Float,  // 0..1 progress at which this particle begins to fade
    val twinkle: Float,    // phase offset so dots shimmer out of sync
)

/** A fresh random spray of dots and lines, regenerated per celebration. */
private fun newBurst(): List<Particle> = List(54) {
    val line = Random.nextFloat() < 0.35f
    Particle(
        angle = Random.nextFloat() * (2f * Math.PI.toFloat()),
        // Wide speed spread so the spray flowers unevenly rather than as a ring.
        distance = 0.35f + Random.nextFloat() * 0.65f,
        color = HaulColors[Random.nextInt(HaulColors.size)],
        isLine = line,
        thickness = if (line) 2.5f + Random.nextFloat() * 2f else 3f + Random.nextFloat() * 3f,
        length = 0.08f + Random.nextFloat() * 0.12f,
        fadeStart = 0.35f + Random.nextFloat() * 0.35f,
        twinkle = Random.nextFloat() * (2f * Math.PI.toFloat()),
    )
}

/** Total burst duration. Deliberately unhurried so the haul feels earned. */
private const val BurstMillis = 1600

/** How far the spray droops downward by the end, as a fraction of the radius. */
private const val Gravity = 0.28f

/**
 * A one-shot firework that bursts from the centre of the shopping area when the
 * user taps **Done** on their trip, congratulating them on the haul (R7.25).
 *
 * Driven by [trigger]: every increment restarts the animation with a fresh
 * random spray and a short vibration. The dots and lines shoot out fast, slow
 * under a little gravity and twinkle out at their own pace, while "Nice Haul!"
 * swells in front and lingers before fading. Idle (trigger 0, or once finished)
 * it draws nothing and never intercepts touches, so the list underneath stays
 * fully interactive.
 */
@Composable
fun HaulCelebration(trigger: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val progress = remember { Animatable(0f) }
    // Regenerated on each run so no two hauls burst the same way.
    val particles = remember(trigger) { newBurst() }

    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        vibrate(context)
        progress.snapTo(0f)
        // Linear clock; the launch/gravity/fade curves are shaped per-frame below.
        progress.animateTo(1f, tween(durationMillis = BurstMillis, easing = LinearEasing))
    }

    val p = progress.value
    // Nothing to draw before the first trigger or after the burst has faded.
    if (trigger == 0 || p >= 1f) return

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = min(size.width, size.height) * 0.42f
            // Fast launch that eases off (decelerate), like real ejecta.
            val launch = 1f - (1f - p) * (1f - p)
            val drop = Gravity * radius * p * p
            particles.forEach { particle ->
                val dx = cos(particle.angle)
                val dy = sin(particle.angle)
                val reach = particle.distance * radius * launch
                val head = Offset(center.x + dx * reach, center.y + dy * reach + drop)
                // Each particle holds, then fades on its own schedule, with a
                // faint shimmer so the spray never dims as one flat sheet.
                val faded = ((p - particle.fadeStart) / (1f - particle.fadeStart))
                    .coerceIn(0f, 1f)
                val shimmer = 0.85f + 0.15f * sin(p * 12f + particle.twinkle)
                val alpha = ((1f - faded) * shimmer).coerceIn(0f, 1f)
                val c = particle.color.copy(alpha = alpha)
                if (particle.isLine) {
                    val tailReach = (reach - particle.length * radius).coerceAtLeast(0f)
                    val tail = Offset(center.x + dx * tailReach, center.y + dy * tailReach + drop)
                    drawLine(
                        color = c,
                        start = tail,
                        end = head,
                        strokeWidth = particle.thickness.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                } else {
                    drawCircle(color = c, radius = particle.thickness.dp.toPx(), center = head)
                }
            }
        }

        // "Nice Haul!" sits in front of the burst: swells in, holds, then fades.
        val textAlpha = when {
            p < 0.15f -> p / 0.15f
            p < 0.7f -> 1f
            else -> ((1f - p) / 0.3f).coerceIn(0f, 1f)
        }
        // Slight overshoot as it settles, so the text feels like it lands.
        val grow = (p / 0.25f).coerceAtMost(1f)
        val textScale = 0.6f + 0.5f * grow - 0.1f * (grow * grow)
        Text(
            text = "Nice Haul!",
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    this.alpha = textAlpha
                    scaleX = textScale
                    scaleY = textScale
                },
        )
    }
}

/** A brief buzz to punctuate the haul. Requires the VIBRATE permission. */
private fun vibrate(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
}
