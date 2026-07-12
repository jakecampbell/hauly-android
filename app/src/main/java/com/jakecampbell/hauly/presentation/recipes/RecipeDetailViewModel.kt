package com.jakecampbell.hauly.presentation.recipes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jakecampbell.hauly.data.sync.ConnectivityObserver
import com.jakecampbell.hauly.domain.model.AddItemResult
import com.jakecampbell.hauly.domain.model.EditItemResult
import com.jakecampbell.hauly.domain.model.Recipe
import com.jakecampbell.hauly.domain.model.RecipeBlock
import com.jakecampbell.hauly.domain.model.ShoppingItem
import com.jakecampbell.hauly.domain.repository.RecipeRepository
import com.jakecampbell.hauly.domain.repository.ShoppingRepository
import com.jakecampbell.hauly.domain.usecase.AddIngredientToList
import com.jakecampbell.hauly.presentation.shopping.AddItemController
import com.jakecampbell.hauly.presentation.shopping.AddItemUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecipeDetailUiState(
    val recipe: Recipe? = null,
    val blocks: List<RecipeBlock> = emptyList(),
    val ingredients: List<ShoppingItem> = emptyList(),
    /** Known store names, for the long-press edit dialog's chips. */
    val storeOptions: List<String> = emptyList(),
    val isRefreshing: Boolean = false,
    val isLoadingBlocks: Boolean = false,
    val isOnline: Boolean = true,
    val loadError: String? = null,
)

@HiltViewModel
class RecipeDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: RecipeRepository,
    private val shoppingRepository: ShoppingRepository,
    private val addIngredientToList: AddIngredientToList,
    connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    private val recipeId: String = checkNotNull(savedStateHandle["recipeId"])

    private val transient = MutableStateFlow(RecipeDetailUiState())

    /** Same type-ahead add dialog as the shopping list, so names stay unique. */
    private val addController =
        AddItemController(viewModelScope, shoppingRepository, connectivityObserver)
    val addState: StateFlow<AddItemUiState> = addController.state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val uiState: StateFlow<RecipeDetailUiState> = combine(
        repository.recipe(recipeId),
        repository.blocks(recipeId),
        repository.ingredients(recipeId),
        combine(connectivityObserver.isOnline, shoppingRepository.storeOptions(), ::Pair),
        transient,
    ) { recipe, blocks, ingredients, (online, storeOptions), transientState ->
        transientState.copy(
            recipe = recipe,
            blocks = blocks,
            ingredients = ingredients,
            storeOptions = storeOptions,
            isOnline = online,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipeDetailUiState())

    init {
        // Instructions render from cache immediately; fetch the latest blocks
        // (with pagination) in the background when possible.
        viewModelScope.launch {
            transient.update { it.copy(isLoadingBlocks = true) }
            repository.refreshRecipeDetail(recipeId).onFailure {
                if (uiState.value.blocks.isEmpty()) {
                    transient.update {
                        it.copy(loadError = "Instructions need an internet connection the first time.")
                    }
                }
            }
            transient.update { it.copy(isLoadingBlocks = false) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            transient.update { it.copy(isRefreshing = true, loadError = null) }
            repository.refreshRecipeDetail(recipeId).onFailure {
                _messages.emit("Couldn't refresh this recipe from Notion.")
            }
            transient.update { it.copy(isRefreshing = false) }
        }
    }

    /**
     * Toggle the Planned ("make it") flag. Deliberately leaves the shopping
     * list untouched — ingredients are added manually by the user.
     */
    fun togglePlanned() {
        val recipe = uiState.value.recipe ?: return
        viewModelScope.launch {
            repository.setPlanned(recipe.id, !recipe.planned)
            _messages.emit(
                if (recipe.planned) "Removed \"${recipe.name}\" from planned"
                else "Planned! \"${recipe.name}\" moved to your make list"
            )
        }
    }

    /** Tap on an ingredient row: flip it between shopped and un-shopped. */
    fun toggleShopped(item: ShoppingItem) {
        viewModelScope.launch {
            shoppingRepository.setShopped(item.localId, !item.shopped)
        }
    }

    /** Delete button in the edit dialog: remove the item from Notion entirely. */
    fun deleteItem(item: ShoppingItem) {
        viewModelScope.launch {
            shoppingRepository.deleteItem(item.localId)
            _messages.emit("Deleted \"${item.name}\"")
        }
    }

    /** Save the long-press edit dialog (name, stores, quantity). */
    fun saveEdit(item: ShoppingItem, name: String, stores: List<String>, quantity: Double?) {
        viewModelScope.launch {
            when (shoppingRepository.updateDetails(item.localId, name, stores, quantity)) {
                EditItemResult.SAVED -> Unit
                EditItemResult.DUPLICATE_NAME ->
                    _messages.emit("An item named \"$name\" already exists")
            }
        }
    }

    // --- Add-ingredient dialog (shared controller) ---

    fun openAddDialog() = addController.open()

    fun dismissAddDialog() = addController.dismiss()

    fun onAddQueryChange(query: String) = addController.onQueryChange(query)

    fun onAddQuantityChange(text: String) = addController.onQuantityChange(text)

    fun selectSuggestion(item: ShoppingItem) = addController.selectSuggestion(item)

    /**
     * Confirm the add dialog: create or reactivate the item with the Grocery
     * store and link it to this recipe. A remote-only suggestion goes through
     * the same path — the create-flush merges by name, so nothing duplicates.
     */
    fun confirmAdd() {
        val input = addController.consumeInput() ?: return
        viewModelScope.launch {
            val result = addIngredientToList(recipeId, input.name, input.quantity)
            val reactivated = result == AddItemResult.REACTIVATED ||
                (result == AddItemResult.CREATED && input.selected?.shopped == true)
            when {
                result == AddItemResult.ALREADY_ACTIVE ->
                    _messages.emit("\"${input.name}\" is already on the list")

                reactivated -> _messages.emit("\"${input.name}\" is back on the list")

                else -> _messages.emit("Added \"${input.name}\" to the list")
            }
        }
    }
}
