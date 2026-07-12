package com.jakecampbell.hauly.presentation.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jakecampbell.hauly.data.sync.ConnectivityObserver
import com.jakecampbell.hauly.domain.model.AddItemResult
import com.jakecampbell.hauly.domain.model.EditItemResult
import com.jakecampbell.hauly.domain.model.ShoppingItem
import com.jakecampbell.hauly.domain.repository.ShoppingRepository
import com.jakecampbell.hauly.domain.usecase.AddItemToShoppingList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The collapsible browse of shopped items for the store in view, fetched from
 * Notion page by page (the shopped set grows without bound). Items already on
 * the active list or in the trip ledger are filtered out, so a tapped item
 * disappears from here the moment it lands back on the list.
 */
data class ShoppedHistoryUiState(
    val expanded: Boolean = false,
    val items: List<ShoppingItem> = emptyList(),
    val canLoadMore: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
)

/** null store filter = show every active item, including uncategorized ones. */
data class ShoppingUiState(
    val items: List<ShoppingItem> = emptyList(),
    val storeOptions: List<String> = emptyList(),
    val tagOptions: List<String> = emptyList(),
    val selectedStore: String? = null,
    val isRefreshing: Boolean = false,
    val isOnline: Boolean = true,
    val pendingEdits: Int = 0,
    val hasLoaded: Boolean = false,
    /** This trip's checked-off items, shown crossed out below the active list. */
    val shoppedItems: List<ShoppingItem> = emptyList(),
)

@HiltViewModel
class ShoppingViewModel @Inject constructor(
    private val repository: ShoppingRepository,
    private val addItemToShoppingList: AddItemToShoppingList,
    connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    private val selectedStore = MutableStateFlow<String?>(null)
    private val isRefreshing = MutableStateFlow(false)

    /** Set once the user (or the startup default) has picked a store view. */
    private var storeChosen = false

    private data class LocalState(
        val store: String?,
        val online: Boolean,
        val refreshing: Boolean,
        val shoppedItems: List<ShoppingItem>,
    )

    private val addController = AddItemController(viewModelScope, repository, connectivityObserver)
    val addState: StateFlow<AddItemUiState> = addController.state

    /** Raw fetched history; the exposed state filters out items already on the list. */
    private data class HistoryData(
        val expanded: Boolean = false,
        val items: List<ShoppingItem> = emptyList(),
        val nextCursor: String? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
    )

    private val history = MutableStateFlow(HistoryData())

    val historyState: StateFlow<ShoppedHistoryUiState> = combine(
        history, repository.activeItems(), repository.tripItems(),
    ) { data, active, trip ->
        val onList = buildSet {
            active.forEach { add(it.name.lowercase()) }
            trip.forEach { add(it.name.lowercase()) }
        }
        ShoppedHistoryUiState(
            expanded = data.expanded,
            items = data.items.filter { it.name.lowercase() !in onList },
            canLoadMore = data.nextCursor != null,
            isLoading = data.isLoading,
            error = data.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShoppedHistoryUiState())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        // Default store view: the leftmost chip in the user's manual store
        // order. Only until the user picks one themselves.
        viewModelScope.launch {
            val stores = repository.storeOptions().first { it.isNotEmpty() }
            if (!storeChosen) selectedStore.value = stores.first()
        }

        // Changing the store view while the history is open restarts it for
        // the new store (the fetch is filtered server-side).
        viewModelScope.launch {
            selectedStore.drop(1).collect {
                if (history.value.expanded) {
                    history.value = HistoryData(expanded = true)
                    loadHistoryPage()
                }
            }
        }
    }

    fun selectStore(store: String?) {
        storeChosen = true
        selectedStore.value = store
    }

    val uiState: StateFlow<ShoppingUiState> = combine(
        repository.activeItems(),
        repository.storeOptions(),
        repository.tagOptions(),
        repository.pendingCount(),
        combine(
            selectedStore, connectivityObserver.isOnline, isRefreshing, repository.tripItems(),
        ) { store, online, refreshing, shopped ->
            LocalState(store, online, refreshing, shopped)
        },
    ) { items, stores, tags, pending, local ->
        ShoppingUiState(
            items = if (local.store == null) items else items.filter { candidate ->
                candidate.stores.any { it.equals(local.store, ignoreCase = true) }
            },
            storeOptions = stores,
            tagOptions = tags,
            selectedStore = local.store,
            isRefreshing = local.refreshing,
            isOnline = local.online,
            pendingEdits = pending,
            hasLoaded = true,
            shoppedItems = local.shoppedItems,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShoppingUiState())

    fun refresh() {
        viewModelScope.launch {
            isRefreshing.value = true
            repository.refresh().onFailure {
                _messages.emit("Couldn't refresh from Notion. Check your connection.")
            }
            isRefreshing.value = false
        }
    }

    fun setShopped(item: ShoppingItem, shopped: Boolean) {
        viewModelScope.launch { repository.setShopped(item.localId, shopped) }
    }

    /** Tap in the shopped section: return the item to the active list. */
    fun unshop(item: ShoppingItem) {
        viewModelScope.launch { repository.setShopped(item.localId, false) }
    }

    /** "Done" button: end the trip and discard the shopped-items tracking. */
    fun finishTrip() {
        viewModelScope.launch {
            repository.finishTrip()
            _messages.emit("Trip finished — nice haul!")
        }
    }

    /** Persist a drag-reorder of the currently displayed rows. */
    fun persistOrder(orderedLocalIds: List<String>) {
        viewModelScope.launch { repository.setManualOrder(orderedLocalIds) }
    }

    /** Persist a drag-reorder of the store filter chips. */
    fun persistStoreOrder(order: List<String>) {
        viewModelScope.launch { repository.setStoreOrder(order) }
    }

    fun assignStores(item: ShoppingItem, stores: List<String>) {
        viewModelScope.launch { repository.assignStores(item.localId, stores) }
    }

    /** Delete button in the edit dialog: remove the item from Notion entirely. */
    fun deleteItem(item: ShoppingItem) {
        viewModelScope.launch {
            repository.deleteItem(item.localId)
            _messages.emit("Deleted \"${item.name}\"")
        }
    }

    /** Save the long-press edit dialog (name, stores, quantity). */
    fun saveEdit(item: ShoppingItem, name: String, stores: List<String>, quantity: Double?) {
        viewModelScope.launch {
            when (repository.updateDetails(item.localId, name, stores, quantity)) {
                EditItemResult.SAVED -> Unit
                EditItemResult.DUPLICATE_NAME ->
                    _messages.emit("An item named \"$name\" already exists")
            }
        }
    }

    // --- Shopped-items history ---

    /** The subtle "show shopped items" toggle. Expanding always fetches fresh. */
    fun toggleHistory() {
        val expanded = !history.value.expanded
        history.value = HistoryData(expanded = expanded)
        if (expanded) loadHistoryPage()
    }

    fun loadMoreHistory() {
        if (!history.value.isLoading) loadHistoryPage()
    }

    /** Tap on a history row: put the item straight back on the active list. */
    fun addFromHistory(item: ShoppingItem) {
        viewModelScope.launch {
            repository.reactivate(item, null, selectedStore.value)
            _messages.emit("\"${item.name}\" is back on the list")
        }
    }

    private fun loadHistoryPage() {
        val requestedStore = selectedStore.value
        val cursor = history.value.nextCursor
        viewModelScope.launch {
            history.update { it.copy(isLoading = true, error = null) }
            val result = repository.shoppedHistory(requestedStore, cursor)
            // A stale response (store view changed, or collapsed) is dropped.
            if (selectedStore.value != requestedStore || !history.value.expanded) return@launch
            result
                .onSuccess { page ->
                    history.update { data ->
                        data.copy(
                            items = (data.items + page.items)
                                .distinctBy { it.name.lowercase() },
                            nextCursor = page.nextCursor,
                            isLoading = false,
                        )
                    }
                }
                .onFailure {
                    history.update {
                        it.copy(
                            isLoading = false,
                            error = "Couldn't load shopped items. Check your connection.",
                        )
                    }
                }
        }
    }

    // --- Add dialog (shared controller) ---

    fun openAddDialog() = addController.open()

    fun dismissAddDialog() = addController.dismiss()

    fun onAddQueryChange(query: String) = addController.onQueryChange(query)

    fun onAddQuantityChange(text: String) = addController.onQuantityChange(text)

    fun selectSuggestion(item: ShoppingItem) = addController.selectSuggestion(item)

    fun confirmAdd() {
        val input = addController.consumeInput() ?: return
        val store = selectedStore.value

        viewModelScope.launch {
            val selected = input.selected
            if (selected != null && selected.localId.isEmpty()) {
                // Remote-only match (found via Notion search, not in the cache).
                repository.reactivate(selected, input.quantity, store)
                _messages.emit("\"${selected.name}\" is back on the list")
            } else {
                when (addItemToShoppingList(input.name, input.quantity, store)) {
                    AddItemResult.CREATED -> _messages.emit("Added \"${input.name}\"")
                    AddItemResult.REACTIVATED -> _messages.emit("\"${input.name}\" is back on the list")
                    AddItemResult.ALREADY_ACTIVE -> _messages.emit("\"${input.name}\" is already on the list")
                }
            }
        }
    }
}

/** "2.0" reads as "2"; fractional quantities keep their decimals. */
fun formatQuantity(quantity: Double): String =
    if (quantity == quantity.toLong().toDouble()) quantity.toLong().toString()
    else quantity.toString()
