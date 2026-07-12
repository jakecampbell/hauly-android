package com.jakecampbell.hauly.presentation.shopping

import com.jakecampbell.hauly.data.sync.ConnectivityObserver
import com.jakecampbell.hauly.domain.model.ShoppingItem
import com.jakecampbell.hauly.domain.repository.ShoppingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State of the type-ahead add dialog. Suggestions blend instant matches from
 * the local cache with debounced results from Notion (which is how shopped,
 * no-longer-cached items are found), deduplicated by name.
 */
data class AddItemUiState(
    val visible: Boolean = false,
    val query: String = "",
    val suggestions: List<ShoppingItem> = emptyList(),
    /** The suggestion the user tapped; cleared if they keep typing. */
    val selected: ShoppingItem? = null,
    val quantityText: String = "",
    val isSearchingRemote: Boolean = false,
) {
    val canConfirm: Boolean get() = query.isNotBlank()
}

/** What the user confirmed in the add dialog. */
data class ConfirmedAdd(
    val name: String,
    val quantity: Double?,
    /** The tapped suggestion, kept only while the final text still matches it. */
    val selected: ShoppingItem?,
)

/**
 * Drives the shared [AddItemDialog]: type-ahead over the item cache plus a
 * debounced remote Notion search, so both the shopping list and the recipe
 * view add items through the same duplicate-preventing flow.
 */
class AddItemController(
    private val scope: CoroutineScope,
    private val repository: ShoppingRepository,
    private val connectivityObserver: ConnectivityObserver,
) {

    private data class AddDialog(
        val visible: Boolean = false,
        val query: String = "",
        val selected: ShoppingItem? = null,
        val quantityText: String = "",
        val remoteMatches: List<ShoppingItem> = emptyList(),
        val isSearchingRemote: Boolean = false,
    )

    private val addDialog = MutableStateFlow(AddDialog())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val localMatches = addDialog
        .map { it.query.trim() }
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.isEmpty()) flowOf(emptyList()) else repository.matchingItems(query)
        }

    val state: StateFlow<AddItemUiState> = combine(addDialog, localMatches) { dialog, local ->
        val query = dialog.query.trim()
        // Once a suggestion is picked the list hides; it reappears when the
        // user edits the name again (which clears the selection).
        val merged = if (dialog.selected != null) emptyList() else {
            (local + dialog.remoteMatches.filter { remote ->
                local.none { it.name.equals(remote.name, ignoreCase = true) }
            })
                .filter { query.isNotEmpty() && it.name.contains(query, ignoreCase = true) }
                .sortedBy { it.name.lowercase() }
                .take(8)
        }
        AddItemUiState(
            visible = dialog.visible,
            query = dialog.query,
            suggestions = merged,
            selected = dialog.selected,
            quantityText = dialog.quantityText,
            isSearchingRemote = dialog.isSearchingRemote,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), AddItemUiState())

    init {
        // Remote leg of the type-ahead: debounced Notion search so shopped
        // items (not cached by design) can be matched and reactivated.
        @OptIn(FlowPreview::class)
        scope.launch {
            addDialog
                .map { it.visible to it.query.trim() }
                .distinctUntilChanged()
                .debounce(350)
                .collectLatest { (visible, query) ->
                    if (!visible || query.isEmpty() || !connectivityObserver.currentlyOnline()) {
                        addDialog.update { it.copy(remoteMatches = emptyList(), isSearchingRemote = false) }
                        return@collectLatest
                    }
                    addDialog.update { it.copy(isSearchingRemote = true) }
                    val results = repository.searchRemote(query).getOrDefault(emptyList())
                    addDialog.update {
                        // Ignore stale results if the user kept typing.
                        if (it.query.trim() == query) {
                            it.copy(remoteMatches = results, isSearchingRemote = false)
                        } else {
                            it.copy(isSearchingRemote = false)
                        }
                    }
                }
        }
    }

    fun open() {
        addDialog.value = AddDialog(visible = true)
    }

    fun dismiss() {
        addDialog.value = AddDialog()
    }

    fun onQueryChange(query: String) {
        addDialog.update { dialog ->
            dialog.copy(
                query = query,
                // Keep the selection only while the text still matches it.
                selected = dialog.selected?.takeIf { it.name == query },
            )
        }
    }

    fun onQuantityChange(text: String) {
        addDialog.update { it.copy(quantityText = text) }
    }

    /** Tapping a suggestion fills the name and pre-populates the quantity. */
    fun selectSuggestion(item: ShoppingItem) {
        addDialog.update {
            it.copy(
                query = item.name,
                selected = item,
                quantityText = item.quantity?.let(::formatQuantity) ?: "",
            )
        }
    }

    /** Read the confirmed input and close the dialog; null when there is nothing to add. */
    fun consumeInput(): ConfirmedAdd? {
        val dialog = addDialog.value
        val name = dialog.query.trim()
        if (name.isEmpty()) return null
        val quantity = dialog.quantityText.trim().toDoubleOrNull()
        val selected = dialog.selected?.takeIf { it.name.equals(name, ignoreCase = true) }
        addDialog.value = AddDialog()
        return ConfirmedAdd(name = name, quantity = quantity, selected = selected)
    }
}
