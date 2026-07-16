package com.jakecampbell.hauly.presentation.recipes

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jakecampbell.hauly.R
import com.jakecampbell.hauly.domain.model.BlockType
import com.jakecampbell.hauly.domain.model.RecipeBlock
import com.jakecampbell.hauly.domain.model.RecipeSection
import com.jakecampbell.hauly.domain.model.ShoppingItem
import com.jakecampbell.hauly.domain.repository.RecipeRepository
import com.jakecampbell.hauly.presentation.common.SwipeDirection
import com.jakecampbell.hauly.presentation.common.SwipeToRevealBox
import com.jakecampbell.hauly.presentation.common.longPressIris
import com.jakecampbell.hauly.presentation.shopping.AddItemDialog
import com.jakecampbell.hauly.presentation.shopping.EditItemDialog
import com.jakecampbell.hauly.presentation.shopping.formatQuantity

/**
 * Recipe view/edit. Four content sections: the Shopping list (the linked
 * ingredient items you can check off), an editable Ingredients text list drawn
 * like ruled paper, editable Instructions, and — when the Notion page still has
 * legacy body content — a read-only "Additional" section. Tapping any
 * ingredient/instruction line strikes it through (local-only focus tracking).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    recipeId: String,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    // Keyed by recipe id so each open recipe gets its own instance while the
    // detail lives inside the Recipes pager page (survives swiping away).
    viewModel: RecipeDetailViewModel = hiltViewModel<RecipeDetailViewModel, RecipeDetailViewModel.Factory>(
        key = recipeId,
    ) { factory -> factory.create(recipeId) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val addState by viewModel.addState.collectAsStateWithLifecycle()
    var editItem by remember { mutableStateOf<ShoppingItem?>(null) }
    // Which text section is being edited, and the working text for it.
    var editingSection by remember { mutableStateOf<RecipeSection?>(null) }
    var editText by remember { mutableStateOf("") }
    var showRename by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        // A successful delete pops us back to the recipe list.
        viewModel.closed.collect { onBack() }
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
            IconButton(
                enabled = state.recipe != null,
                onClick = { showRename = true },
            ) {
                Icon(Icons.Default.Edit, contentDescription = "Rename recipe")
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

                // --- Source link (clickable web link, editable) ---
                item {
                    RecipeLinkRow(
                        url = state.recipe?.url.orEmpty(),
                        enabled = state.recipe != null,
                        onEdit = { showUrlDialog = true },
                    )
                }

                // --- Shopping list (linked ingredient items) ---
                item {
                    Text(
                        "Shopping",
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
                    SwipeToUnlinkBox(onUnlink = { viewModel.removeFromRecipe(ingredient) }) {
                        ShoppingItemRow(
                            ingredient = ingredient,
                            onToggleShopped = { viewModel.toggleShopped(ingredient) },
                            onLongPress = { editItem = ingredient },
                        )
                    }
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
                        Text("add item")
                    }
                }

                // --- Ingredients (ruled-paper text list) ---
                item {
                    Column {
                        SectionEditHeader(
                            title = "Ingredients",
                            enabled = state.recipe != null,
                            onEdit = {
                                editText = state.recipe?.ingredients.orEmpty()
                                editingSection = RecipeSection.INGREDIENTS
                            },
                        )
                        if (editingSection == RecipeSection.INGREDIENTS) {
                            SectionEditor(
                                value = editText,
                                onValueChange = { editText = it },
                                onSave = {
                                    viewModel.saveIngredients(editText)
                                    editingSection = null
                                },
                                onCancel = { editingSection = null },
                            )
                        } else {
                            TextLines(
                                text = state.recipe?.ingredients.orEmpty(),
                                struck = state.struckLines[RecipeSection.INGREDIENTS].orEmpty(),
                                ruled = true,
                                supportHeadings = true,
                                emptyHint = "No ingredients yet — tap the pencil to add.",
                                onToggle = { viewModel.toggleLine(RecipeSection.INGREDIENTS, it) },
                            )
                        }
                    }
                }

                // --- Instructions (editable text) ---
                item {
                    Column {
                        SectionEditHeader(
                            title = "Instructions",
                            enabled = state.recipe != null,
                            onEdit = {
                                editText = state.recipe?.instructions.orEmpty()
                                editingSection = RecipeSection.INSTRUCTIONS
                            },
                        )
                        if (editingSection == RecipeSection.INSTRUCTIONS) {
                            SectionEditor(
                                value = editText,
                                onValueChange = { editText = it },
                                onSave = {
                                    viewModel.saveInstructions(editText)
                                    editingSection = null
                                },
                                onCancel = { editingSection = null },
                            )
                        } else {
                            TextLines(
                                text = state.recipe?.instructions.orEmpty(),
                                struck = state.struckLines[RecipeSection.INSTRUCTIONS].orEmpty(),
                                ruled = false,
                                emptyHint = "No instructions yet — tap the pencil to add.",
                                onToggle = { viewModel.toggleLine(RecipeSection.INSTRUCTIONS, it) },
                            )
                        }
                    }
                }

                // --- Additional (legacy Notion page body, read-only) ---
                if (state.blocks.isNotEmpty()) {
                    item {
                        Text(
                            "Additional",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(state.blocks, key = { it.id }) { block ->
                        BlockView(block)
                    }
                }

                // --- Delete recipe (online-first; two taps to confirm) ---
                item {
                    Column {
                    Spacer(Modifier.height(24.dp))
                    TextButton(
                        enabled = state.recipe != null,
                        onClick = {
                            if (confirmDelete) {
                                viewModel.deleteRecipe()
                                confirmDelete = false
                            } else {
                                confirmDelete = true
                            }
                        },
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            text = if (confirmDelete) "Really delete this recipe?" else "Delete recipe",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
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

    if (showRename) {
        RecipeRenameDialog(
            currentName = state.recipe?.name.orEmpty(),
            onDismiss = { showRename = false },
            onConfirm = {
                viewModel.rename(it)
                showRename = false
            },
        )
    }

    if (showUrlDialog) {
        RecipeUrlDialog(
            currentUrl = state.recipe?.url.orEmpty(),
            onDismiss = { showUrlDialog = false },
            onConfirm = {
                viewModel.saveUrl(it)
                showUrlDialog = false
            },
        )
    }

    editItem?.let { item ->
        EditItemDialog(
            item = item,
            storeOptions = state.storeOptions,
            tagOptions = state.tagOptions,
            onDismiss = { editItem = null },
            onConfirm = { name, stores, tags, quantity ->
                viewModel.saveEdit(item, name, stores, tags, quantity)
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
 * The recipe's source link: a clickable web link that opens in the browser,
 * with a pencil to edit it. When unset, shows a subtle "Add a link" affordance.
 */
@Composable
private fun RecipeLinkRow(url: String, enabled: Boolean, onEdit: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Link,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        if (url.isBlank()) {
            Text(
                text = "Add a link",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = enabled) { onEdit() }
                    .padding(vertical = 8.dp),
            )
        } else {
            Text(
                text = url,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { openUrl(context, url) }
                    .padding(vertical = 8.dp),
            )
            IconButton(enabled = enabled, onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit link",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Open a recipe link in the browser, defaulting to https when no scheme is given. */
private fun openUrl(context: Context, raw: String) {
    val url = raw.trim()
    val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, normalized.toUri())) }
}

/** Section title with a trailing edit (pencil) affordance. */
@Composable
private fun SectionEditHeader(title: String, enabled: Boolean, onEdit: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(enabled = enabled, onClick = onEdit) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Multi-line editor for a text section (one ingredient/step per line). */
@Composable
private fun SectionEditor(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("One per line") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onSave) { Text("Save") }
        }
    }
}

/**
 * Render a newline-separated text section as tappable lines. Each non-blank
 * line can be struck through (crossed out and dimmed) to track progress; when
 * [ruled] each line gets a divider beneath it, like ruled paper. Line indices
 * are relative to the raw split so strikes stay stable across blank lines.
 *
 * When [supportHeadings], a line whose text begins with `--` is treated as a
 * section heading within the list: the marker is stripped and the line is drawn
 * with extra whitespace above and a slight background highlight, and it is not
 * tappable/strikeable (it's a label, not a checkable item).
 */
@Composable
private fun TextLines(
    text: String,
    struck: Set<Int>,
    ruled: Boolean,
    emptyHint: String,
    onToggle: (Int) -> Unit,
    supportHeadings: Boolean = false,
) {
    val lines = text.split("\n")
    if (lines.none { it.isNotBlank() }) {
        Text(
            text = emptyHint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        lines.forEachIndexed { index, line ->
            if (line.isBlank()) return@forEachIndexed
            if (supportHeadings && line.trimStart().startsWith("--")) {
                IngredientHeading(text = line.trimStart().removePrefix("--").trim())
                return@forEachIndexed
            }
            val isStruck = index in struck
            Text(
                text = line,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (isStruck) TextDecoration.LineThrough else null,
                color = if (isStruck) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(index) }
                    .padding(vertical = 10.dp),
            )
            if (ruled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/**
 * A heading line within the ingredient list (a line prefixed with `--`).
 * Rendered with breathing room above it and a subtle filled background so it
 * visually groups the ingredients that follow.
 */
@Composable
private fun IngredientHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/**
 * Swiping an ingredient row left unlinks it from this recipe (R8.14). Left is
 * the direction the pager doesn't need here: Recipes is its last page, so only
 * the right swipe (back to Shopping) has to survive — the mirror image of the
 * shopping list, where right is the free one (R7.18).
 */
@Composable
private fun SwipeToUnlinkBox(
    onUnlink: () -> Unit,
    content: @Composable () -> Unit,
) {
    SwipeToRevealBox(
        direction = SwipeDirection.LEFT,
        icon = Icons.Default.LinkOff,
        label = "Remove",
        onTriggered = onUnlink,
        content = content,
    )
}

/**
 * A linked shopping item on the recipe: qty · name · shopped state. Tapping
 * anywhere toggles shopped/un-shopped, long-pressing opens the shared edit
 * dialog.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShoppingItemRow(
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
