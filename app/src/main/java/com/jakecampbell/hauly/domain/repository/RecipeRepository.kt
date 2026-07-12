package com.jakecampbell.hauly.domain.repository

import com.jakecampbell.hauly.domain.model.AddItemResult
import com.jakecampbell.hauly.domain.model.Recipe
import com.jakecampbell.hauly.domain.model.RecipeBlock
import com.jakecampbell.hauly.domain.model.ShoppingItem
import kotlinx.coroutines.flow.Flow

interface RecipeRepository {

    fun recipes(): Flow<List<Recipe>>

    fun recipe(id: String): Flow<Recipe?>

    /** Cached instruction blocks for a recipe, in page order. */
    fun blocks(recipeId: String): Flow<List<RecipeBlock>>

    /** Shopping items related to this recipe (its ingredients). */
    fun ingredients(recipeId: String): Flow<List<ShoppingItem>>

    /** Refresh the recipe list from Notion. */
    suspend fun refreshRecipes(): Result<Unit>

    /** Refresh one recipe's page blocks (paginated) and its ingredient relations. */
    suspend fun refreshRecipeDetail(recipeId: String): Result<Unit>

    /**
     * Toggle the Notion `Planned` checkbox (the "make it" flag). Offline-safe:
     * queued locally and flushed by the sync worker. Does not touch ingredients.
     */
    suspend fun setPlanned(recipeId: String, planned: Boolean)

    /**
     * Add an ingredient to the shopping list from a recipe. Creates the item
     * and links the relation if it doesn't exist; otherwise reactivates it so
     * it shows on the active list. Either way the [DEFAULT_INGREDIENT_STORE]
     * store is applied, and [quantity] (when provided) replaces the item's.
     */
    suspend fun addIngredient(recipeId: String, name: String, quantity: Double?): AddItemResult

    companion object {
        /** Store automatically applied to ingredients added from a recipe. */
        const val DEFAULT_INGREDIENT_STORE = "Grocery"
    }
}
