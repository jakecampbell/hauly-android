package com.jakecampbell.hauly.presentation.recipes

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jakecampbell.hauly.R
import com.jakecampbell.hauly.domain.model.BlockType
import com.jakecampbell.hauly.domain.model.RecipeBlock
import com.jakecampbell.hauly.domain.model.ShoppingItem
import com.jakecampbell.hauly.domain.repository.RecipeRepository
import com.jakecampbell.hauly.presentation.common.longPressIris
import com.jakecampbell.hauly.presentation.shopping.AddItemDialog
import com.jakecampbell.hauly.presentation.shopping.EditItemDialog
import com.jakecampbell.hauly.presentation.shopping.formatQuantity

/**
 * Recipe view for cooking: tappable ingredients (shop/un-shop) with their
 * quantities, an add-ingredient flow sharing the shopping list's type-ahead
 * dialog, and the page's instruction blocks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    viewModel: RecipeDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val addState by viewModel.addState.collectAsStateWithLifecycle()
    var editItem by remember { mutableStateOf<ShoppingItem?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = state.recipe?.name ?: "Recipe",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (state.isLoadingBlocks) {
                CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
            }
            val context = LocalContext.current
            val recipeId = state.recipe?.id
            IconButton(
                enabled = recipeId != null,
                onClick = {
                    // Notion page URLs are the page id without dashes.
                    val url = "https://www.notion.so/${recipeId.orEmpty().replace("-", "")}"
                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_notion),
                    contentDescription = "Open in Notion",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 32.dp,
                ),
            ) {
                // --- Planned ("make it") toggle ---
                item {
                    val planned = state.recipe?.planned == true
                    FilledTonalButton(
                        onClick = viewModel::togglePlanned,
                        enabled = state.recipe != null,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) {
                        Icon(
                            imageVector = if (planned) Icons.Default.EventBusy
                            else Icons.Default.EventAvailable,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(if (planned) "Don't make it" else "Make it")
                    }
                }

                // --- Ingredients ---
                item {
                    Text(
                        "Ingredients",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                if (state.ingredients.isEmpty()) {
                    item {
                        Text(
                            "No linked ingredients cached. Add one below.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.ingredients, key = { it.localId }) { ingredient ->
                    IngredientRow(
                        ingredient = ingredient,
                        onToggleShopped = { viewModel.toggleShopped(ingredient) },
                        onLongPress = { editItem = ingredient },
                    )
                }
                item {
                    TextButton(
                        onClick = viewModel::openAddDialog,
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text("Add ingredient")
                    }
                }

                // --- Instructions ---
                item {
                    Text(
                        "Instructions",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                }
                when {
                    state.loadError != null && state.blocks.isEmpty() -> item {
                        Text(
                            state.loadError!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    state.blocks.isEmpty() && !state.isLoadingBlocks -> item {
                        Text(
                            "This recipe's Notion page has no content yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> items(state.blocks, key = { it.id }) { block ->
                        BlockView(block)
                    }
                }
            }
        }
    }

    if (addState.visible) {
        AddItemDialog(
            state = addState,
            currentStore = RecipeRepository.DEFAULT_INGREDIENT_STORE,
            onQueryChange = viewModel::onAddQueryChange,
            onQuantityChange = viewModel::onAddQuantityChange,
            onSelectSuggestion = viewModel::selectSuggestion,
            onDismiss = viewModel::dismissAddDialog,
            onConfirm = viewModel::confirmAdd,
        )
    }

    editItem?.let { item ->
        EditItemDialog(
            item = item,
            storeOptions = state.storeOptions,
            onDismiss = { editItem = null },
            onConfirm = { name, stores, quantity ->
                viewModel.saveEdit(item, name, stores, quantity)
                editItem = null
            },
            onDelete = {
                viewModel.deleteItem(item)
                editItem = null
            },
        )
    }
}

/**
 * Qty · name · shopped state; tapping anywhere toggles shopped/un-shopped,
 * long-pressing opens the shared edit dialog.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IngredientRow(
    ingredient: ShoppingItem,
    onToggleShopped: () -> Unit,
    onLongPress: () -> Unit,
) {
    val dimmed = MaterialTheme.colorScheme.onSurfaceVariant
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Growing iris while the press is held, warning that the
            // long-press edit is about to trigger.
            .longPressIris(interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onToggleShopped,
                onLongClick = onLongPress,
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // An empty Qty in Notion means one of the item is needed.
            text = formatQuantity(ingredient.quantity ?: 1.0),
            style = MaterialTheme.typography.bodyLarge,
            color = dimmed,
            modifier = Modifier.widthIn(min = 24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = ingredient.name,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (ingredient.shopped) TextDecoration.LineThrough else null,
            color = if (ingredient.shopped) dimmed.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (ingredient.shopped) Icons.Default.CheckCircle
            else Icons.Default.RadioButtonUnchecked,
            contentDescription = if (ingredient.shopped) "Shopped — tap to un-shop"
            else "Tap to mark shopped",
            tint = if (ingredient.shopped) MaterialTheme.colorScheme.primary else dimmed,
        )
    }
}

@Composable
private fun BlockView(block: RecipeBlock) {
    when (block.type) {
        BlockType.HEADING_1 -> Text(
            block.text,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )

        BlockType.HEADING_2 -> Text(
            block.text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )

        BlockType.HEADING_3 -> Text(
            block.text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
        )

        BlockType.BULLETED_LIST_ITEM -> Row(modifier = Modifier.padding(vertical = 3.dp)) {
            Text("•", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(10.dp))
            Text(block.text, style = MaterialTheme.typography.bodyLarge)
        }

        BlockType.NUMBERED_LIST_ITEM -> Row(modifier = Modifier.padding(vertical = 3.dp)) {
            Text(
                "${block.ordinal}.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(block.text, style = MaterialTheme.typography.bodyLarge)
        }

        BlockType.TODO -> Row(
            modifier = Modifier.padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (block.checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(block.text, style = MaterialTheme.typography.bodyLarge)
        }

        BlockType.QUOTE, BlockType.CALLOUT -> Text(
            block.text,
            style = MaterialTheme.typography.bodyLarge,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )

        BlockType.DIVIDER -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        BlockType.PARAGRAPH, BlockType.UNSUPPORTED -> {
            if (block.text.isNotBlank()) {
                Text(
                    block.text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
