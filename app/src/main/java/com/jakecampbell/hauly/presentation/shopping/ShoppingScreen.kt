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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jakecampbell.hauly.domain.model.ShoppingItem
import com.jakecampbell.hauly.domain.model.SyncStatus
import com.jakecampbell.hauly.presentation.common.EmptyState
import com.jakecampbell.hauly.presentation.common.OfflineBanner
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

            StoreFilterRow(
                options = state.storeOptions,
                selected = state.selectedStore,
                onSelect = viewModel::selectStore,
            )

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
                    showEmpty = state.items.isEmpty() && state.shoppedItems.isEmpty() &&
                        state.hasLoaded,
                    selectedStore = state.selectedStore,
                    history = historyState,
                    onCheckedChange = viewModel::setShopped,
                    onStoreClick = { storePickerItem = it },
                    onLongPress = { editItem = it },
                    onOrderPersist = viewModel::persistOrder,
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
 * Drag-sortable active list with this trip's shopped items grouped below it
 * and, at the bottom, the collapsible browse of previously shopped items.
 * Active rows are mirrored into local state so reordering is instant; the
 * final order is persisted when the drag ends and Room re-emits it back.
 */
@Composable
private fun ReorderableItemList(
    items: List<ShoppingItem>,
    shoppedItems: List<ShoppingItem>,
    showEmpty: Boolean,
    selectedStore: String?,
    history: ShoppedHistoryUiState,
    onCheckedChange: (ShoppingItem, Boolean) -> Unit,
    onStoreClick: (ShoppingItem) -> Unit,
    onLongPress: (ShoppingItem) -> Unit,
    onOrderPersist: (List<String>) -> Unit,
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

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        items(localItems, key = { it.localId }) { item ->
            ReorderableItem(reorderableState, key = item.localId) { isDragging ->
                Surface(shadowElevation = if (isDragging) 4.dp else 0.dp) {
                    ShoppingItemRow(
                        item = item,
                        onCheckedChange = { onCheckedChange(item, it) },
                        onStoreClick = { onStoreClick(item) },
                        onLongPress = { onLongPress(item) },
                        dragHandle = {
                            DragHandle(
                                onDragStopped = { onOrderPersist(localItems.map { it.localId }) },
                            )
                        },
                    )
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
                ShoppedItemRow(item = item, onClick = { onUnshop(item) })
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

@Composable
private fun StoreFilterRow(
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    // The selected store's label renders in the primary blue.
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedLabelColor = MaterialTheme.colorScheme.primary,
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options) { store ->
            FilterChip(
                selected = selected == store,
                onClick = { onSelect(if (selected == store) null else store) },
                label = { Text(store) },
                colors = chipColors,
            )
        }
        // "All" always sits at the end of the store list.
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("All") },
                colors = chipColors,
            )
        }
    }
}

/** Tap toggles shopped; long-press opens the edit dialog. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShoppingItemRow(
    item: ShoppingItem,
    onCheckedChange: (Boolean) -> Unit,
    onStoreClick: () -> Unit,
    onLongPress: () -> Unit,
    dragHandle: @Composable () -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(8.dp))
                Text(
                    // An empty Qty in Notion means one of the item is needed.
                    text = "Need: ${formatQuantity(item.quantity ?: 1.0)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val subtitle = buildList {
                if (item.stores.isNotEmpty()) add(item.stores.joinToString(", "))
                if (item.syncStatus == SyncStatus.PENDING_CREATE ||
                    item.syncStatus == SyncStatus.PENDING_UPDATE
                ) add("pending sync")
            }.joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onStoreClick) {
            Icon(
                Icons.Default.Storefront,
                contentDescription = "Assign store",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        dragHandle()
    }
}

