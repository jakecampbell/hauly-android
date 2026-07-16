package com.jakecampbell.hauly.presentation.shopping

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jakecampbell.hauly.domain.model.ShoppingItem
import com.jakecampbell.hauly.domain.model.SyncStatus
import com.jakecampbell.hauly.presentation.common.EmptyState
import com.jakecampbell.hauly.presentation.common.OfflineBanner
import com.jakecampbell.hauly.presentation.common.SwipeDirection
import com.jakecampbell.hauly.presentation.common.SwipeToRevealBox
import com.jakecampbell.hauly.presentation.common.longPressIris
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * The main screen: the active shopping list, filterable by store, drag-sortable
 * via the handle on each row, with a type-ahead add dialog that doubles as
 * search and a persistent un-shop bar for the last checked-off item.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(
    snackbarHostState: SnackbarHostState,
    viewModel: ShoppingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val addState by viewModel.addState.collectAsStateWithLifecycle()
    val historyState by viewModel.historyState.collectAsStateWithLifecycle()
    var storePickerItem by remember { mutableStateOf<ShoppingItem?>(null) }
    var tagPickerItem by remember { mutableStateOf<ShoppingItem?>(null) }
    var editItem by remember { mutableStateOf<ShoppingItem?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::openAddDialog) {
                Icon(Icons.Default.Add, contentDescription = "Add item")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = "Get your haul! :)",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )

            if (!state.isOnline) {
                OfflineBanner(pendingEdits = state.pendingEdits)
            }

            // The chips and the group toggle share one row: the chips take what
            // room is left of the toggle, and scroll under a fade to reach it.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StoreFilterRow(
                    options = state.storeOptions,
                    counts = state.storeCounts,
                    selected = state.selectedStore,
                    onSelect = viewModel::selectStore,
                    onOrderPersist = viewModel::persistStoreOrder,
                    modifier = Modifier.weight(1f),
                )
                GroupToggleButton(
                    enabled = state.groupByTags,
                    onToggle = viewModel::toggleGrouping,
                )
            }

            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                // Always the list (even when empty) so the shopped-items
                // browse below it stays reachable and pull-to-refresh works.
                ReorderableItemList(
                    items = state.items,
                    shoppedItems = state.shoppedItems,
                    groups = state.groups,
                    groupByTags = state.groupByTags,
                    showEmpty = state.items.isEmpty() && state.shoppedItems.isEmpty() &&
                        state.hasLoaded,
                    selectedStore = state.selectedStore,
                    history = historyState,
                    onCheckedChange = viewModel::setShopped,
                    onStoreClick = { storePickerItem = it },
                    onTagClick = { tagPickerItem = it },
                    onLongPress = { editItem = it },
                    onOrderPersist = viewModel::persistOrder,
                    onDiscard = viewModel::discard,
                    onUnshop = viewModel::unshop,
                    onFinishTrip = viewModel::finishTrip,
                    onToggleHistory = viewModel::toggleHistory,
                    onLoadMoreHistory = viewModel::loadMoreHistory,
                    onAddFromHistory = viewModel::addFromHistory,
                )
            }
        }
    }

    if (addState.visible) {
        AddItemDialog(
            state = addState,
            currentStore = state.selectedStore,
            onQueryChange = viewModel::onAddQueryChange,
            onQuantityChange = viewModel::onAddQuantityChange,
            onSelectSuggestion = viewModel::selectSuggestion,
            onDismiss = viewModel::dismissAddDialog,
            onConfirm = viewModel::confirmAdd,
        )
    }

    storePickerItem?.let { item ->
        StorePickerDialog(
            item = item,
            options = state.storeOptions,
            onDismiss = { storePickerItem = null },
            onConfirm = { stores ->
                viewModel.assignStores(item, stores)
                storePickerItem = null
            },
        )
    }

    tagPickerItem?.let { item ->
        TagPickerDialog(
            item = item,
            options = state.tagOptions,
            onDismiss = { tagPickerItem = null },
            onConfirm = { tags ->
                viewModel.assignTags(item, tags)
                tagPickerItem = null
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
 * Drag-sortable active list with this trip's shopped items grouped below it
 * and, at the bottom, the collapsible browse of previously shopped items.
 * Active rows are mirrored into local state so reordering is instant; the
 * final order is persisted when the drag ends and Room re-emits it back.
 *
 * When [groupByTags] is on the active rows are replaced by [groups] — tag
 * sections, no drag handles — while the trip and history sections below are
 * unaffected either way.
 */
@Composable
private fun ReorderableItemList(
    items: List<ShoppingItem>,
    shoppedItems: List<ShoppingItem>,
    groups: List<TagGroup>,
    groupByTags: Boolean,
    showEmpty: Boolean,
    selectedStore: String?,
    history: ShoppedHistoryUiState,
    onCheckedChange: (ShoppingItem, Boolean) -> Unit,
    onStoreClick: (ShoppingItem) -> Unit,
    onTagClick: (ShoppingItem) -> Unit,
    onLongPress: (ShoppingItem) -> Unit,
    onOrderPersist: (List<String>) -> Unit,
    onDiscard: (ShoppingItem) -> Unit,
    onUnshop: (ShoppingItem) -> Unit,
    onFinishTrip: () -> Unit,
    onToggleHistory: () -> Unit,
    onLoadMoreHistory: () -> Unit,
    onAddFromHistory: (ShoppingItem) -> Unit,
) {
    val localItems = remember(items) { items.toMutableStateList() }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // Guard against drags that overshoot into the shopped section below.
        if (to.index in localItems.indices && from.index in localItems.indices) {
            localItems.apply { add(to.index, removeAt(from.index)) }
        }
    }
    // Only on "All": inside a store view the store is a given, so the
    // affordance is noise (R7.3).
    val showStoreButton = selectedStore == null

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        if (groupByTags) {
            groupedItems(
                groups = groups,
                onCheckedChange = onCheckedChange,
                onStoreClick = onStoreClick,
                showStoreButton = showStoreButton,
                onTagClick = onTagClick,
                onLongPress = onLongPress,
                onDiscard = onDiscard,
            )
        } else {
            items(localItems, key = { it.localId }) { item ->
                ReorderableItem(reorderableState, key = item.localId) { isDragging ->
                    SwipeToDiscardBox(onDiscard = { onDiscard(item) }) {
                        Surface(shadowElevation = if (isDragging) 4.dp else 0.dp) {
                            ShoppingItemRow(
                                item = item,
                                onCheckedChange = { onCheckedChange(item, it) },
                                onStoreClick = { onStoreClick(item) },
                                showStoreButton = showStoreButton,
                                onLongPress = { onLongPress(item) },
                                trailing = {
                                    DragHandle(
                                        onDragStopped = { onOrderPersist(localItems.map { it.localId }) },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        if (showEmpty) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Default.ShoppingCart,
                    title = if (selectedStore == null) "Nothing to buy" else
                        "Nothing to buy at $selectedStore",
                    subtitle = "Add items with the + button, or pull down to refresh from Notion.",
                    modifier = Modifier.fillParentMaxHeight(0.6f),
                    scrollable = false,
                )
            }
        }

        if (shoppedItems.isNotEmpty()) {
            item(key = "shopped-header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Shopped (${shoppedItems.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onFinishTrip) { Text("Done") }
                }
            }
            items(shoppedItems, key = { "shopped-${it.localId}" }) { item ->
                SwipeToDiscardBox(onDiscard = { onDiscard(item) }) {
                    ShoppedItemRow(item = item, onClick = { onUnshop(item) })
                }
            }
        }

        shoppedHistorySection(
            history = history,
            selectedStore = selectedStore,
            onToggleHistory = onToggleHistory,
            onLoadMoreHistory = onLoadMoreHistory,
            onAddFromHistory = onAddFromHistory,
        )
    }
}

/**
 * The active list split into tag sections. An item carrying several tags shows
 * up under each of them, so rows are keyed by tag *and* id — keying by id alone
 * would duplicate keys and throw. Rows keep every affordance except the drag
 * handle: manual ordering is suspended while grouped (R7.21).
 */
private fun LazyListScope.groupedItems(
    groups: List<TagGroup>,
    onCheckedChange: (ShoppingItem, Boolean) -> Unit,
    onStoreClick: (ShoppingItem) -> Unit,
    showStoreButton: Boolean,
    onTagClick: (ShoppingItem) -> Unit,
    onLongPress: (ShoppingItem) -> Unit,
    onDiscard: (ShoppingItem) -> Unit,
) {
    groups.forEach { group ->
        item(key = "group-${group.name}") { TagGroupHeader(name = group.name) }

        items(group.items, key = { "group-${group.name}-${it.localId}" }) { item ->
            SwipeToDiscardBox(onDiscard = { onDiscard(item) }) {
                ShoppingItemRow(
                    item = item,
                    onCheckedChange = { onCheckedChange(item, it) },
                    onStoreClick = { onStoreClick(item) },
                    showStoreButton = showStoreButton,
                    onLongPress = { onLongPress(item) },
                    // The drag handle's slot: nothing to drag while grouped,
                    // and tags are what this view is arranged by.
                    trailing = { TagButton(onClick = { onTagClick(item) }) },
                )
            }
        }
    }
}

/** Tag name in light gray, with a rule running from it to the right edge. */
@Composable
private fun TagGroupHeader(name: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = 8.dp).weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        )
    }
}

/**
 * The group-by-tag toggle, at the right end of the store chip row: blue when
 * grouping is on, gray when off — the same on/off signal the selected store
 * chip uses.
 */
@Composable
private fun GroupToggleButton(enabled: Boolean, onToggle: () -> Unit) {
    IconButton(
        onClick = onToggle,
        modifier = Modifier.padding(start = 4.dp, end = 12.dp).size(32.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Category,
            contentDescription = if (enabled) "Show items ungrouped"
            else "Group items by tag",
            tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The collapsible "previously shopped" browse: a subtle toggle, then compact
 * tap-to-re-add rows. The set can be huge, so it renders lazily and fetches
 * one page at a time behind a "Show more" row.
 */
private fun LazyListScope.shoppedHistorySection(
    history: ShoppedHistoryUiState,
    selectedStore: String?,
    onToggleHistory: () -> Unit,
    onLoadMoreHistory: () -> Unit,
    onAddFromHistory: (ShoppingItem) -> Unit,
) {
    item(key = "history-toggle") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            TextButton(onClick = onToggleHistory) {
                Icon(
                    imageVector = if (history.expanded) Icons.Default.ExpandLess
                    else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    text = when {
                        !history.expanded && selectedStore != null ->
                            "Show shopped items for $selectedStore"

                        !history.expanded -> "Show shopped items"

                        else -> "Hide shopped items"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (!history.expanded) return

    if (history.items.isNotEmpty()) {
        item(key = "history-hint") {
            Text(
                text = "Tap an item to put it back on the list",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }

    items(history.items, key = { "history-${it.remoteId}" }) { item ->
        HistoryItemRow(item = item, onClick = { onAddFromHistory(item) })
    }

    when {
        history.isLoading -> item(key = "history-loading") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        history.error != null -> item(key = "history-error") {
            Text(
                text = history.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        history.items.isEmpty() -> item(key = "history-empty") {
            Text(
                text = "Nothing shopped here yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        history.canLoadMore -> item(key = "history-more") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = onLoadMoreHistory) {
                    Text(
                        text = "Show more",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Compact history row: one tap re-adds the item to the active list. */
@Composable
private fun HistoryItemRow(item: ShoppingItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.AddCircleOutline,
            contentDescription = "Add to list",
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            modifier = Modifier.padding(end = 12.dp).size(20.dp),
        )
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        item.quantity?.let {
            Text(
                text = "× ${formatQuantity(it)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/** Crossed-out, grayed trip row; tapping returns the item to the active list. */
@Composable
private fun ShoppedItemRow(item: ShoppingItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = TextDecoration.LineThrough,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "tap to un-shop",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

/**
 * Swiping a row right discards the item (R7.18). Right is the direction the
 * pager doesn't need here: the shopping page is its first, so only the left
 * swipe (to Recipes) has to survive.
 */
@Composable
private fun SwipeToDiscardBox(
    onDiscard: () -> Unit,
    content: @Composable () -> Unit,
) {
    SwipeToRevealBox(
        direction = SwipeDirection.RIGHT,
        icon = Icons.Default.RemoveCircleOutline,
        label = "Discard",
        onTriggered = onDiscard,
        content = content,
    )
}

@Composable
private fun ReorderableCollectionItemScope.DragHandle(onDragStopped: () -> Unit) {
    Icon(
        imageVector = Icons.Default.DragIndicator,
        contentDescription = "Reorder",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(end = 12.dp)
            .draggableHandle(onDragStopped = onDragStopped),
    )
}

/**
 * How much of the chip row's right edge dissolves into the background, and the
 * end padding that buys the last chip room to be scrolled fully clear of it.
 */
private val STORE_FADE_WIDTH = 36.dp

/**
 * Store chips in the user's manually chosen order (long-press a chip to drag
 * it; a plain tap still selects it). "All" is fixed at the end, never draggable.
 *
 * The row shares its line with the group toggle (R7.21), so chips scrolling
 * toward it fade out against the background rather than running into it. The
 * fade is paid for by [STORE_FADE_WIDTH] of end padding: at full scroll the last
 * chip comes to rest just clear of the gradient, fully opaque and tappable. The
 * gradient is drawn, not laid out, so it never intercepts a tap or a drag.
 */
@Composable
private fun StoreFilterRow(
    options: List<String>,
    counts: Map<String, Int>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onOrderPersist: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The selected store's label renders in the primary blue.
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedLabelColor = MaterialTheme.colorScheme.primary,
    )
    val localOptions = remember(options) { options.toMutableStateList() }
    // Deliberately non-saveable: unlike the item list, this row must always
    // open scrolled to the leftmost chip, never restore a prior scroll offset
    // when the Shopping tab is revisited (saveState/restoreState on the
    // bottom-nav graph would otherwise preserve it).
    val lazyListState = remember { LazyListState() }

    // While options is still empty (before storeOptions() first emits), "All"
    // is the row's only item, at index 0. LazyListState anchors scroll by key,
    // so once real stores arrive and "All" shifts to the last slot, Compose
    // keeps that key in view — pushing the row to the end. Force it back to
    // the start the one time the list populates from empty.
    var hasScrolledToInitialStores by remember { mutableStateOf(false) }
    LaunchedEffect(options) {
        if (options.isNotEmpty() && !hasScrolledToInitialStores) {
            lazyListState.scrollToItem(0)
            hasScrolledToInitialStores = true
        }
    }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // "All" lives outside localOptions, so it can never be a drag target.
        if (to.index in localOptions.indices && from.index in localOptions.indices) {
            localOptions.apply { add(to.index, removeAt(from.index)) }
        }
    }

    // Solid, so the chips dissolve into the screen rather than under a scrim.
    // Fading to the same hue at zero alpha (not Color.Transparent, which is a
    // transparent *black*) keeps a dark band out of the middle of the ramp.
    val fadeColor = MaterialTheme.colorScheme.background
    LazyRow(
        state = lazyListState,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = STORE_FADE_WIDTH,
            top = 8.dp,
            bottom = 8.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.drawWithContent {
            drawContent()
            val fade = STORE_FADE_WIDTH.toPx()
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(fadeColor.copy(alpha = 0f), fadeColor),
                    startX = size.width - fade,
                    endX = size.width,
                ),
                topLeft = Offset(size.width - fade, 0f),
                size = Size(fade, size.height),
            )
        },
    ) {
        items(localOptions, key = { it }) { store ->
            ReorderableItem(reorderableState, key = store) { isDragging ->
                FilterChip(
                    selected = selected == store,
                    onClick = { onSelect(if (selected == store) null else store) },
                    label = { StoreChipLabel(store = store, count = counts[store] ?: 0) },
                    colors = chipColors,
                    modifier = Modifier
                        .alpha(if (isDragging) 0.7f else 1f)
                        .longPressDraggableHandle(
                            onDragStopped = { onOrderPersist(localOptions.toList()) },
                        ),
                )
            }
        }
        // "All" always sits at the end of the store list.
        item(key = "all") {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("All") },
                colors = chipColors,
            )
        }
    }
}

/**
 * A store chip's label: the name, then how many active items it holds. The name
 * takes no explicit color so it keeps the chip's selected/unselected treatment;
 * the count is deliberately smaller and dimmer, being metadata rather than the
 * label. A store with nothing on its list shows no number at all (R7.24).
 */
@Composable
private fun StoreChipLabel(store: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(store)
        if (count > 0) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/**
 * Tap toggles shopped; long-press opens the edit dialog. [trailing] is the
 * drag handle on the ungrouped list and the tag button when grouped, where
 * there is nothing to drag.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShoppingItemRow(
    item: ShoppingItem,
    onCheckedChange: (Boolean) -> Unit,
    onStoreClick: () -> Unit,
    showStoreButton: Boolean,
    onLongPress: () -> Unit,
    trailing: @Composable () -> Unit,
) {
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
                onClick = { onCheckedChange(!item.shopped) },
                onLongClick = onLongPress,
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = item.shopped, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge)
            val subtitle = buildList {
                // An empty Qty in Notion means one of the item is needed.
                add("Need: ${formatQuantity(item.quantity ?: 1.0)}")
                if (item.syncStatus == SyncStatus.PENDING_CREATE ||
                    item.syncStatus == SyncStatus.PENDING_UPDATE
                ) add("pending sync")
            }.joinToString(" · ")
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showStoreButton) {
            IconButton(onClick = onStoreClick) {
                Icon(
                    Icons.Default.Storefront,
                    contentDescription = "Assign store",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

/** Grouped-list counterpart to the drag handle: a quick tag edit. */
@Composable
private fun TagButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Default.Sell,
            contentDescription = "Assign tags",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

