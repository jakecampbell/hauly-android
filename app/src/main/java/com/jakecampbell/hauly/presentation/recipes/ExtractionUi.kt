package com.jakecampbell.hauly.presentation.recipes

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jakecampbell.hauly.domain.model.ExtractionStatus
import com.jakecampbell.hauly.domain.model.RecipeExtraction
import com.jakecampbell.hauly.presentation.common.longPressIris

/** What the long-press clipboard preview card should show. */
sealed interface ClipPreview {
    /** No hauly-backend beta token is configured. */
    data object NoToken : ClipPreview

    /** The clipboard holds no usable text. */
    data object Empty : ClipPreview

    /** Clipboard text ready to submit for extraction. */
    data class Ready(val text: String) : ClipPreview
}

/**
 * A FloatingActionButton look-alike that also accepts a long-press. Material3's
 * FloatingActionButton bakes in a plain onClick, so this rebuilds its visuals
 * (shape, colors, elevation) on a Surface and layers the app's long-press iris
 * affordance under a combinedClickable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LongPressFab(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                // Growing iris while the press is held, warning that the
                // long-press clipboard flow is about to trigger.
                .longPressIris(interactionSource)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/**
 * The card revealed by long-pressing the FAB: a peek at the current clipboard
 * text that submits it for extraction when tapped (R8.15). Tap-outside
 * dismissal is handled by the caller via a scrim underneath.
 */
@Composable
fun ClipboardPreviewCard(
    preview: ClipPreview,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = modifier
            .then(
                if (preview is ClipPreview.Ready) {
                    Modifier.clickable { onSubmit(preview.text) }
                } else {
                    Modifier
                }
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (preview) {
                is ClipPreview.Ready -> {
                    Text(
                        "Paste recipe from clipboard",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        preview.text.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "%,d characters".format(preview.text.length),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ClipPreview.NoToken -> Text(
                    "Add your Hauly beta token in Settings to extract recipes from the clipboard.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ClipPreview.Empty -> Text(
                    "Clipboard is empty — copy a recipe first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One extraction's status, pinned above the recipe list (R8.16): a pulsing
 * "Parsing recipe…" while the backend works, the extracted title awaiting
 * review once done, or the failure reason with Retry/Dismiss.
 */
@Composable
fun ExtractionRow(
    extraction: RecipeExtraction,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (extraction.status) {
        ExtractionStatus.SUBMITTING, ExtractionStatus.PENDING, ExtractionStatus.PROCESSING -> {
            val pulse by rememberInfiniteTransition(label = "extraction-pulse").animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                label = "extraction-pulse-alpha",
            )
            ExtractionSurface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = pulse),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    // A cold-started backend can hold the submit POST for tens
                    // of seconds, so say what's actually happening.
                    if (extraction.status == ExtractionStatus.SUBMITTING) {
                        "Sending to recipe service…"
                    } else {
                        "Parsing recipe…"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel extraction")
                }
            }
        }

        ExtractionStatus.COMPLETED -> ExtractionSurface(
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.clickable(onClick = onOpen),
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    extraction.title.ifBlank { "Recipe ready" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Tap to review",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss extraction")
            }
        }

        // NO_RECIPE renders like a failure but offers no Retry — the backend
        // has already judged the text, and resubmitting it can't change that.
        ExtractionStatus.NO_RECIPE, ExtractionStatus.FAILED -> ExtractionSurface(
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                extraction.error ?: "Extraction failed.",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (extraction.status == ExtractionStatus.FAILED) {
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss extraction")
            }
        }
    }
}

/** Shared chrome for [ExtractionRow] states, matching the PlannedBox styling. */
@Composable
private fun ExtractionSurface(
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
