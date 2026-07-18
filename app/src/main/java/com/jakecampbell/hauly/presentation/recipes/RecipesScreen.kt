package com.jakecampbell.hauly.presentation.recipes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jakecampbell.hauly.domain.model.Recipe
import com.jakecampbell.hauly.domain.model.RecipeExtraction
import com.jakecampbell.hauly.domain.model.RecipeSort
import com.jakecampbell.hauly.presentation.common.EmptyState
import com.jakecampbell.hauly.presentation.common.OfflineBanner
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipesScreen(
    snackbarHostState: SnackbarHostState,
    onRecipeClick: (String) -> Unit,
    viewModel: RecipesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    /** Clipboard peek revealed by long-pressing the FAB; null while hidden. */
    var clipPreview by remember { mutableStateOf<ClipPreview?>(null) }
    /** Whether the max-sized free-text recipe dialog is open (R8.15). */
    var showFreeText by remember { mutableStateOf(false) }
    /** Completed extraction being reviewed in the (prefilled) create dialog. */
    var prefillExtraction by remember { mutableStateOf<RecipeExtraction?>(null) }
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Re-sorting rearranges everything under the user, so send them back to the
    // top rather than leaving them stranded mid-list. Armed by the tap, but the
    // scroll itself must wait for the re-sorted rows to arrive: setSort travels
    // through DataStore, and as the new order lands LazyColumn re-anchors the
    // scroll onto whatever item was on top (it tracks items by key), which would
    // undo a scroll started at tap time. Scrolling from this effect lands in the
    // same composition as the new order, and an explicit scroll drops that key
    // anchor. The flag keeps it tied to a real tap: this effect also runs when
    // the pager re-creates the page (R9.1), where the restored scroll position
    // must be left alone.
    var scrollToTopOnSort by remember { mutableStateOf(false) }
    val selectSort: (RecipeSort) -> Unit = { mode ->
        scrollToTopOnSort = true
        viewModel.setSort(mode)
    }
    LaunchedEffect(state.sort) {
        if (scrollToTopOnSort) {
            scrollToTopOnSort = false
            // Not animated: the rows just reordered, so animating through a list
            // the user never saw is motion without meaning.
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        // Opening the freshly created recipe closes the create dialog.
        viewModel.created.collect { newId ->
            showCreate = false
            prefillExtraction = null
            onRecipeClick(newId)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    // statusBarsPadding matches the shopping screen, whose nested Scaffold
    // re-applies the status-bar inset above its title.
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Recipes",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = state.sort == RecipeSort.ALPHA,
                onClick = { selectSort(RecipeSort.ALPHA) },
                label = { Text("A–Z") },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = state.sort == RecipeSort.RECENT,
                onClick = { selectSort(RecipeSort.RECENT) },
                label = { Text("Recent") },
            )
        }

        SearchBar(
            query = state.query,
            onQueryChange = viewModel::setQuery,
            onClear = { viewModel.setQuery("") },
        )

        if (!state.isOnline) {
            OfflineBanner(pendingEdits = 0)
        }

        // In-flight clipboard extractions stay pinned above the list — even
        // while searching, since they're transient status rather than content.
        state.extractions.forEach { extraction ->
            ExtractionRow(
                extraction = extraction,
                onOpen = { prefillExtraction = extraction },
                onRetry = { viewModel.retryExtraction(extraction.id) },
                onDismiss = { viewModel.dismissExtraction(extraction.id) },
            )
        }

        // Planned recipes stay frozen above the scrolling list, but the search
        // results collapse into a single flat list so every match is visible.
        if (!state.isSearching && state.planned.isNotEmpty()) {
            PlannedBox(recipes = state.planned, onRecipeClick = onRecipeClick)
        }

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.isSearching && state.isEmpty -> {
                    EmptyState(
                        icon = Icons.Filled.SearchOff,
                        title = "No matches",
                        subtitle = "No recipes match \"${state.query.trim()}\".",
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                state.isEmpty && state.hasLoaded -> {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        title = "No recipes yet",
                        subtitle = "Add recipes in Notion, then pull down to refresh.",
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                else -> {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        val rows = if (state.isSearching) state.matches else state.others
                        if (!state.isSearching && state.planned.isNotEmpty()) {
                            item { SectionHeader("All recipes") }
                        }
                        items(rows, key = { it.id }) { recipe ->
                            RecipeRow(recipe = recipe, onClick = { onRecipeClick(recipe.id) })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }

        // While the clipboard preview is up, a full-screen invisible scrim
        // dismisses it on any outside tap.
        if (clipPreview != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { clipPreview = null },
            )
        }

        LongPressFab(
            onClick = { showCreate = true },
            onLongClick = {
                // Reading the clipboard is a suspend call on this API; the
                // preview state lands once the text is in hand.
                scope.launch {
                    val text = clipboard.getClipEntry()
                        ?.clipData
                        ?.getItemAt(0)
                        ?.coerceToText(context)
                        ?.toString()
                        .orEmpty()
                    clipPreview = when {
                        !state.hasBackendToken -> ClipPreview.NoToken
                        text.isBlank() -> ClipPreview.Empty
                        else -> ClipPreview.Ready(text)
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "New recipe")
        }

        // The long-press reveal: the clipboard peek, plus a "Free text" option
        // above it whenever a beta token makes the backend flow available.
        clipPreview?.let { preview ->
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(start = 32.dp, end = 16.dp, bottom = 88.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.hasBackendToken) {
                    FreeTextOption(onClick = {
                        clipPreview = null
                        showFreeText = true
                    })
                }
                ClipboardPreviewCard(
                    preview = preview,
                    onSubmit = { text ->
                        viewModel.submitClipboard(text)
                        clipPreview = null
                    },
                )
            }
        }
    }

    if (showFreeText) {
        RecipeFreeTextDialog(
            onDismiss = { showFreeText = false },
            onCreate = { text ->
                viewModel.submitFreeText(text)
                showFreeText = false
            },
        )
    }

    if (showCreate || prefillExtraction != null) {
        val prefill = prefillExtraction
        RecipeCreateDialog(
            initialName = prefill?.title.orEmpty(),
            initialIngredients = prefill?.ingredients.orEmpty(),
            initialInstructions = prefill?.instructions.orEmpty(),
            onDismiss = {
                // Cancelling a review keeps the extraction row for later.
                showCreate = false
                prefillExtraction = null
            },
            onConfirm = { name, ingredients, instructions, url ->
                viewModel.createRecipe(name, ingredients, instructions, url, prefill?.id)
            },
        )
    }
}

/**
 * The planned recipes, grouped inside a rounded, tonally raised box that is
 * pinned above the scrolling list. Beyond a max height the box scrolls
 * internally so a big plan can't push the list off the screen.
 */
@Composable
private fun PlannedBox(recipes: List<Recipe>, onRecipeClick: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "Planned",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                recipes.forEachIndexed { index, recipe ->
                    RecipeRow(recipe = recipe, onClick = { onRecipeClick(recipe.id) })
                    if (index < recipes.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Search field pinned under the title. Filters the list across every recipe
 * property; the trailing clear button appears whenever there is text to clear
 * or the field is focused, so the user can always drop focus and dismiss the
 * keyboard.
 */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .onFocusChanged { isFocused = it.isFocused },
        singleLine = true,
        placeholder = { Text("Search recipes") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty() || isFocused) {
                // Clearing empties the field and drops focus so the keyboard
                // closes — otherwise there's no way out of an active search.
                // While focused with no text, this acts as a plain "drop focus".
                IconButton(onClick = {
                    onClear()
                    focusManager.clearFocus()
                }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                }
            }
        },
        // The keyboard's action key dismisses the keyboard without clearing the query.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun RecipeRow(recipe: Recipe, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = recipe.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
