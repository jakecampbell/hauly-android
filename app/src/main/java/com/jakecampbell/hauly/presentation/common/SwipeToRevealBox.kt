package com.jakecampbell.hauly.presentation.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Which way the row travels to reveal its action. */
enum class SwipeDirection { RIGHT, LEFT }

/** Past this fraction of the row's width, releasing triggers the action. */
private const val COMMIT_THRESHOLD = 0.4f

/**
 * Reveals an action backdrop as [content] is dragged one way, and fires
 * [onTriggered] when the drag passes [COMMIT_THRESHOLD] of the row's width.
 *
 * Deliberately hand-rolled rather than Material's SwipeToDismissBox: that claims
 * every horizontal drag on the row, which would swallow the home pager's own
 * swipe (R9.1). This consumes the gesture only once it is unambiguously headed
 * in [direction] — the opposite drag, and any vertical one, is left unconsumed,
 * so the pager and the list's own scrolling still see it. That direction test is
 * the whole safeguard (R7.20), which is why both swipe surfaces share this one
 * implementation rather than each keeping a copy that could drift.
 *
 * A caller may therefore only use the direction the pager doesn't need on that
 * page: right on the shopping list (R7.18), left on the recipe detail (R8.14),
 * which is the pager's last page and so has nothing to its left.
 */
@Composable
fun SwipeToRevealBox(
    direction: SwipeDirection,
    icon: ImageVector,
    label: String,
    onTriggered: () -> Unit,
    content: @Composable () -> Unit,
) {
    val offsetX = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(0) }
    // +1 travels right, -1 travels left; progress stays 0..1 either way.
    val dirSign = if (direction == SwipeDirection.RIGHT) 1f else -1f
    val progress = if (width == 0) 0f else (offsetX.value * dirSign / width).coerceIn(0f, 1f)
    val armed = progress >= COMMIT_THRESHOLD
    // Deepens the moment the drag is far enough to act: "release now".
    val backdrop by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.error.copy(alpha = if (armed) 0.30f else 0.16f),
        label = "swipeBackdrop",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width }
            .pointerInput(direction) {
                coroutineScope {
                    val scope = this
                    // Travel is 0..width one way, -width..0 the other.
                    fun clampOffset(value: Float): Float =
                        if (dirSign > 0f) value.coerceIn(0f, width.toFloat())
                        else value.coerceIn(-width.toFloat(), 0f)

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var overSlop = 0f
                        val drag = awaitTouchSlopOrCancellation(down.id) { change, over ->
                            // Not consuming leaves the event for the pager (the
                            // other direction) or the LazyColumn (vertical).
                            if (over.x * dirSign > 0f && abs(over.x) > abs(over.y)) {
                                overSlop = over.x
                                change.consume()
                            }
                        } ?: return@awaitEachGesture

                        scope.launch { offsetX.snapTo(clampOffset(overSlop)) }
                        horizontalDrag(drag.id) { change ->
                            val target = clampOffset(offsetX.value + change.positionChange().x)
                            scope.launch { offsetX.snapTo(target) }
                            change.consume()
                        }
                        scope.launch {
                            if (abs(offsetX.value) >= width * COMMIT_THRESHOLD) {
                                // Slide clear first; the row then leaves for
                                // good when Room re-emits the list without it.
                                offsetX.animateTo(dirSign * width, tween(durationMillis = 180))
                                onTriggered()
                            } else {
                                offsetX.animateTo(0f)
                            }
                        }
                    }
                }
            },
    ) {
        if (progress > 0f) {
            Row(
                modifier = Modifier
                    .matchParentSize()
                    // Fades in over the run-up, full strength once armed.
                    .alpha((progress / COMMIT_THRESHOLD).coerceAtMost(1f))
                    .background(backdrop)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                // The action sits on the side the row uncovers.
                horizontalArrangement = if (dirSign > 0f) Arrangement.Start else Arrangement.End,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 8.dp).size(20.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                // Opaque, so the backdrop only shows where the row has moved off.
                .background(MaterialTheme.colorScheme.background),
        ) {
            content()
        }
    }
}
