package com.jakecampbell.hauly.presentation.recipes.cook

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jakecampbell.hauly.presentation.theme.CookMagenta
import kotlinx.coroutines.delay

/**
 * A single-line cook timer (R8.20): `reset | time | start/pause`, all icons. The
 * time counts up (stopwatch) or down (timer) in MM:SS, ticking while running.
 * While idle the minutes and seconds are **tapped and typed directly** — two
 * inline fields, no dialog; setting a positive value makes it a countdown, 0
 * leaves it a stopwatch. While running only Pause is enabled — the fields and
 * reset lock, so an accidental tap can't wipe a running timer. A finished
 * countdown flashes magenta until it is reset — while it does, a tap **anywhere
 * on the row** dismisses the alarm, not just the reset icon.
 */
@Composable
fun CookTimerRow(
    timer: TimerState,
    onStartPause: () -> Unit,
    onReset: () -> Unit,
    onSetTime: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Re-read the clock while running so the display advances each second. The
    // timer math itself is clock-based, so this only drives recomposition.
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedTick(running = timer.isRunning) { now = SystemClock.elapsedRealtime() }

    val running = timer.isRunning
    val timeColor = if (timer.isTimer) CookMagenta else MaterialTheme.colorScheme.onSurface

    // A finished countdown pulses its background magenta until reset.
    val flash = flashAlpha(active = timer.finished)
    val rowColor = if (timer.finished) CookMagenta.copy(alpha = flash) else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowColor, RoundedCornerShape(8.dp))
            // While the alarm is sounding, tapping anywhere on the row dismisses
            // it — the reset icon is just one of many targets.
            .then(if (timer.finished) Modifier.clickable(onClick = onReset) else Modifier)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onReset, enabled = !running) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Reset timer",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (running) {
                // Locked to a plain, ticking readout while running.
                Text(
                    text = formatTimer(timer.displayMs(now)),
                    style = MaterialTheme.typography.titleMedium,
                    color = timeColor,
                )
            } else {
                TimeEditor(
                    displayMs = timer.displayMs(now),
                    color = timeColor,
                    onSetTime = onSetTime,
                )
            }
        }
        IconButton(onClick = onStartPause) {
            Icon(
                imageVector = if (running) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (running) "Pause timer" else "Start timer",
                tint = CookMagenta,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/**
 * The idle time as two inline, directly-editable fields (minutes : seconds).
 * Tapping either field selects its digits so the first keystroke replaces the
 * placeholder; every edit commits straight to the timer via [onSetTime]. While a
 * field is focused it is left alone (so the caret doesn't jump); when focus
 * leaves, the fields re-sync from the timer — which also normalises padding and
 * rolls, say, 90s into 1:30.
 */
@Composable
private fun TimeEditor(
    displayMs: Long,
    color: Color,
    onSetTime: (Long) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var minutes by remember { mutableStateOf(TextFieldValue("")) }
    var seconds by remember { mutableStateOf(TextFieldValue("")) }
    var minFocused by remember { mutableStateOf(false) }
    var secFocused by remember { mutableStateOf(false) }
    val anyFocused = minFocused || secFocused

    // Mirror the timer while not being edited.
    LaunchedEffect(displayMs, anyFocused) {
        if (!anyFocused) {
            val totalSeconds = displayMs / 1000
            minutes = TextFieldValue("%02d".format(totalSeconds / 60))
            seconds = TextFieldValue("%02d".format(totalSeconds % 60))
        }
    }

    fun commit() {
        val mins = minutes.text.toLongOrNull() ?: 0L
        val secs = seconds.text.toLongOrNull() ?: 0L
        onSetTime((mins * 60 + secs) * 1000L)
    }

    val textStyle = MaterialTheme.typography.titleMedium.copy(
        color = color,
        textAlign = TextAlign.Center,
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        BasicTextField(
            value = minutes,
            onValueChange = { input ->
                // Minutes can exceed 59 (a 90-minute braise), so allow 3 digits.
                val digits = input.text.filter(Char::isDigit).take(3)
                minutes = input.copy(text = digits, selection = TextRange(digits.length))
                commit()
            },
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(CookMagenta),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier
                .width(32.dp)
                .onFocusChanged { focus ->
                    minFocused = focus.isFocused
                    // Select all on focus so typing replaces the placeholder.
                    if (focus.isFocused) {
                        minutes = minutes.copy(selection = TextRange(0, minutes.text.length))
                    }
                },
        )
        Text(":", style = textStyle)
        BasicTextField(
            value = seconds,
            onValueChange = { input ->
                val digits = input.text.filter(Char::isDigit).take(2)
                seconds = input.copy(text = digits, selection = TextRange(digits.length))
                commit()
            },
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(CookMagenta),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier
                .width(28.dp)
                .onFocusChanged { focus ->
                    secFocused = focus.isFocused
                    if (focus.isFocused) {
                        seconds = seconds.copy(selection = TextRange(0, seconds.text.length))
                    }
                },
        )
    }
}

/** Ticks [onTick] roughly 4×/second while [running], then once more on stop. */
@Composable
private fun LaunchedTick(running: Boolean, onTick: () -> Unit) {
    LaunchedEffect(running) {
        while (running) {
            onTick()
            delay(250)
        }
        onTick()
    }
}

/**
 * Alpha that pulses 0.15↔0.5 while [active], else 0. The transition is always
 * created (its result just ignored when inactive) so the composable structure
 * stays constant across recompositions.
 */
@Composable
private fun flashAlpha(active: Boolean): Float {
    val transition = rememberInfiniteTransition(label = "timerFlash")
    val pulse by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "timerFlashAlpha",
    )
    return if (active) pulse else 0f
}

/** MM:SS, where minutes can exceed 59 (e.g. a 90-minute braise shows 90:00). */
internal fun formatTimer(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

/** Convenience: the timer row bound to a [CookSession] timer via its [TimerKey]. */
@Composable
fun CookTimerRow(
    timer: TimerState,
    key: TimerKey,
    onStartPause: (TimerKey) -> Unit,
    onReset: (TimerKey) -> Unit,
    onSetTime: (TimerKey, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    CookTimerRow(
        timer = timer,
        onStartPause = { onStartPause(key) },
        onReset = { onReset(key) },
        onSetTime = { onSetTime(key, it) },
        modifier = modifier,
    )
}
