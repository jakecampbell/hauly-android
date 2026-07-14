package com.jakecampbell.hauly.presentation.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jakecampbell.hauly.data.sync.ConnectivityObserver
import com.jakecampbell.hauly.domain.model.AddItemResult
import com.jakecampbell.hauly.domain.model.EditItemResult
import com.jakecampbell.hauly.domain.model.Recipe
import com.jakecampbell.hauly.domain.model.RecipeBlock
import com.jakecampbell.hauly.domain.model.RecipeSection
import com.jakecampbell.hauly.domain.model.ShoppingItem
import com.jakecampbell.hauly.domain.repository.RecipeRepository
import com.jakecampbell.hauly.domain.repository.ShoppingRepository
import com.jakecampbell.hauly.domain.usecase.AddIngredientToList
import com.jakecampbell.hauly.presentation.shopping.AddItemController
import com.jakecampbell.hauly.presentation.shopping.AddItemUiState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
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

data class RecipeDetailUiState(
    val recipe: Recipe? = null,
    val blocks: List<RecipeBlock> = emptyList(),
    val ingredients: List<ShoppingItem> = emptyList(),
    /** Struck (crossed-out) line indices per editable text section — local-only. */
    val struckLines: Map<RecipeSection, Set<Int>> = emptyMap(),
    /** Known store names, for the long-press edit dialog's chips. */
    val storeOptions: List<String> = emptyList(),
    /** Known tag names, for the long-press edit dialog's chips. */
    val tagOptions: List<String> = emptyList(),
    val isRefreshing: Boolean = false,
    val isLoadingBlocks: Boolean = false,
    val isOnline: Boolean = true,
    val loadError: String? = null,
)

@HiltViewModel(assistedFactory = RecipeDetailViewModel.Factory::class)
class RecipeDetailViewModel @AssistedInject constructor(
    @Assisted private val recipeId: String,
    private val repository: RecipeRepository,
    private val shoppingRepository: ShoppingRepository,
    private val addIngredientToList: AddIngredientToList,
    connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    /** Assisted factory so the detail can be scoped/keyed by recipe id when it
     * lives inside the Recipes pager page rather than a nav route. */
    @AssistedFactory
    interface Factory {
        fun create(recipeId: String): RecipeDetailViewModel
    }

    private val transient = MutableStateFlow(RecipeDetailUiState())

    /** Same type-ahead add dialog as the shopping list, so names stay unique. */
    private val addController =
        AddItemController(viewModelScope, shoppingRepository, connectivityObserver)
    val addState: StateFlow<AddItemUiState> = addController.state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Emitted after a successful delete so the screen can navigate back. */
    private val _closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closed: SharedFlow<Unit> = _closed.asSharedFlow()

    /** The non-recipe inputs, bundled to stay within combine's arity. */
    private data class Extras(
        val online: Boolean,
        val storeOptions: List<String>,
        val tagOptions: List<String>,
        val transient: RecipeDetailUiState,
    )

    private val extras = combine(
        connectivityObserver.isOnline,
        shoppingRepository.storeOptions(),
        shoppingRepository.tagOptions(),
        transient,
    ) { online, storeOptions, tagOptions, transientState ->
        Extras(online, storeOptions, tagOptions, transientState)
    }

    val uiState: StateFlow<RecipeDetailUiState> = combine(
        repository.recipe(recipeId),
        repository.blocks(recipeId),
        repository.ingredients(recipeId),
        repository.struckLines(recipeId),
        extras,
    ) { recipe, blocks, ingredients, struck, extra ->
        extra.transient.copy(
            recipe = recipe,
            blocks = blocks,
            ingredients = ingredients,
            struckLines = struck,
            storeOptions = extra.storeOptions,
            tagOptions = extra.tagOptions,
            isOnline = extra.online,
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

    /** Save the edited ingredient text (offline-queued). */
    fun saveIngredients(text: String) {
        viewModelScope.launch { repository.saveIngredients(recipeId, text.trim()) }
    }

    /** Save the edited instruction text (offline-queued). */
    fun saveInstructions(text: String) {
        viewModelScope.launch { repository.saveInstructions(recipeId, text.trim()) }
    }

    /** Save the recipe's source link (offline-queued). Blank clears it. */
    fun saveUrl(url: String) {
        viewModelScope.launch { repository.saveUrl(recipeId, url.trim()) }
    }

    /** Rename the recipe (offline-queued). Blank names are ignored. */
    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.renameRecipe(recipeId, trimmed) }
    }

    /** Tap a text line: toggle its local-only "done" strike. */
    fun toggleLine(section: RecipeSection, lineIndex: Int) {
        viewModelScope.launch { repository.toggleLineMark(recipeId, section, lineIndex) }
    }

    /** Delete the whole recipe (online-first). Navigates back on success. */
    fun deleteRecipe() {
        val recipe = uiState.value.recipe ?: return
        if (!uiState.value.isOnline) {
            viewModelScope.launch { _messages.emit("Deleting a recipe needs an internet connection.") }
            return
        }
        viewModelScope.launch {
            repository.deleteRecipe(recipe.id)
                .onSuccess { _closed.emit(Unit) }
                .onFailure { _messages.emit("Couldn't delete this recipe.") }
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

    /** Save the long-press edit dialog (name, stores, tags, quantity). */
    fun saveEdit(
        item: ShoppingItem,
        name: String,
        stores: List<String>,
        tags: List<String>,
        quantity: Double?,
    ) {
        viewModelScope.launch {
            when (shoppingRepository.updateDetails(item.localId, name, stores, tags, quantity)) {
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
     * Confirm the add dialog: create the item (or link an existing one) with the
     * Grocery store and attach it to this recipe. An existing item keeps its
     * current shopped state — a shopped item stays crossed out rather than being
     * pulled back onto the active list. A remote-only suggestion goes through the
     * same path — the create-flush merges by name, so nothing duplicates.
     */
    fun confirmAdd() {
        val input = addController.consumeInput() ?: return
        viewModelScope.launch {
            when (addIngredientToList(recipeId, input.name, input.quantity, input.selected)) {
                AddItemResult.ALREADY_ACTIVE ->
                    _messages.emit("\"${input.name}\" is already on the list")

                AddItemResult.ADDED_SHOPPED ->
                    _messages.emit("Added \"${input.name}\" — it's already shopped")

                else -> _messages.emit("Added \"${input.name}\" to the list")
            }
        }
    }
}
