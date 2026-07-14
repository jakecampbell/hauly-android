package com.jakecampbell.hauly.presentation.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jakecampbell.hauly.data.sync.ConnectivityObserver
import com.jakecampbell.hauly.domain.model.Recipe
import com.jakecampbell.hauly.domain.model.RecipeSort
import com.jakecampbell.hauly.domain.repository.RecipeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * True when [query] (already trimmed, non-empty) is found in any of the recipe's
 * text properties — name, ingredients, instructions, or source link — case-insensitively.
 */
private fun Recipe.matches(query: String): Boolean {
    return name.contains(query, ignoreCase = true) ||
        ingredients.contains(query, ignoreCase = true) ||
        instructions.contains(query, ignoreCase = true) ||
        url.contains(query, ignoreCase = true)
}

data class RecipesUiState(
    /** Recipes flagged Planned, shown in their own section above the rest. */
    val planned: List<Recipe> = emptyList(),
    val others: List<Recipe> = emptyList(),
    val sort: RecipeSort = RecipeSort.ALPHA,
    /** The active search query; blank when search is inactive. */
    val query: String = "",
    val isRefreshing: Boolean = false,
    val isOnline: Boolean = true,
    val hasLoaded: Boolean = false,
) {
    val isEmpty: Boolean get() = planned.isEmpty() && others.isEmpty()

    /** True while a non-blank query is filtering the list. */
    val isSearching: Boolean get() = query.isNotBlank()

    /** All matching recipes as one flat list, used while searching. */
    val matches: List<Recipe> get() = planned + others
}

@HiltViewModel
class RecipesViewModel @Inject constructor(
    private val repository: RecipeRepository,
    connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    private val isRefreshing = MutableStateFlow(false)
    private val query = MutableStateFlow("")

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Emitted with the new recipe id after a successful create, to open it. */
    private val _created = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val created: SharedFlow<String> = _created.asSharedFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating

    val uiState: StateFlow<RecipesUiState> = combine(
        repository.recipes(),
        repository.recipeSort(),
        query,
        isRefreshing,
        connectivityObserver.isOnline,
    ) { recipes, sortMode, searchQuery, refreshing, online ->
        val sorted = when (sortMode) {
            RecipeSort.ALPHA -> recipes.sortedBy { it.name.lowercase() }
            RecipeSort.RECENT -> recipes.sortedByDescending { it.lastEditedAt }
        }
        val q = searchQuery.trim()
        val filtered = if (q.isEmpty()) sorted else sorted.filter { it.matches(q) }
        val (planned, others) = filtered.partition { it.planned }
        RecipesUiState(
            planned = planned,
            others = others,
            sort = sortMode,
            query = searchQuery,
            isRefreshing = refreshing,
            isOnline = online,
            hasLoaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipesUiState())

    /** Persisted, so the choice survives a tab switch and an app restart. */
    fun setSort(mode: RecipeSort) {
        viewModelScope.launch { repository.setRecipeSort(mode) }
    }

    /** Update the active search query; blank clears the filter. */
    fun setQuery(value: String) {
        query.value = value
    }

    fun refresh() {
        viewModelScope.launch {
            isRefreshing.value = true
            repository.refreshRecipes().onFailure {
                _messages.emit("Couldn't refresh recipes from Notion.")
            }
            isRefreshing.value = false
        }
    }

    /**
     * Create a recipe (online-first). Emits the new id via [created] on success
     * so the caller can open it. Blank names are rejected.
     */
    fun createRecipe(name: String, ingredients: String, instructions: String, url: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            viewModelScope.launch { _messages.emit("Give the recipe a name.") }
            return
        }
        if (!uiState.value.isOnline) {
            viewModelScope.launch { _messages.emit("Creating a recipe needs an internet connection.") }
            return
        }
        viewModelScope.launch {
            _isCreating.value = true
            repository.createRecipe(trimmed, ingredients.trim(), instructions.trim(), url.trim())
                .onSuccess { _created.emit(it) }
                .onFailure { _messages.emit("Couldn't create the recipe.") }
            _isCreating.value = false
        }
    }
}
