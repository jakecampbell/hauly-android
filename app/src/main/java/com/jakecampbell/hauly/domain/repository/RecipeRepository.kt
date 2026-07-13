package com.jakecampbell.hauly.domain.repository

import com.jakecampbell.hauly.domain.model.AddItemResult
import com.jakecampbell.hauly.domain.model.Recipe
import com.jakecampbell.hauly.domain.model.RecipeBlock
import com.jakecampbell.hauly.domain.model.RecipeSection
import com.jakecampbell.hauly.domain.model.ShoppingItem
import kotlinx.coroutines.flow.Flow

interface RecipeRepository {

    fun recipes(): Flow<List<Recipe>>

    fun recipe(id: String): Flow<Recipe?>

    /** Cached instruction blocks for a recipe, in page order. */
    fun blocks(recipeId: String): Flow<List<RecipeBlock>>

    /** Shopping items related to this recipe (its ingredients). */
    fun ingredients(recipeId: String): Flow<List<ShoppingItem>>

    /**
     * Local-only struck-line indices per section (the cook's "where am I"
     * markers). Never synced to Notion.
     */
    fun struckLines(recipeId: String): Flow<Map<RecipeSection, Set<Int>>>

    /** Toggle a single line's struck state (local-only). */
    suspend fun toggleLineMark(recipeId: String, section: RecipeSection, lineIndex: Int)

    /**
     * Edit the recipe's ingredient/instruction text or rename it. Offline-safe:
     * queued as PENDING_UPDATE and flushed by the sync worker. Editing a text
     * section clears that section's line strikes (line positions shift).
     */
    suspend fun saveIngredients(recipeId: String, text: String)

    suspend fun saveInstructions(recipeId: String, text: String)

    suspend fun saveUrl(recipeId: String, url: String)

    suspend fun renameRecipe(recipeId: String, name: String)

    /**
     * Create a new recipe. Online-first: writes to Notion, then caches the row.
     * Returns the new recipe's id on success. Fails when offline.
     */
    suspend fun createRecipe(
        name: String,
        ingredients: String,
        instructions: String,
        url: String,
    ): Result<String>

    /**
     * Delete a recipe. Online-first: archives the Notion page, then removes the
     * local row and its relations. Fails when offline.
     */
    suspend fun deleteRecipe(recipeId: String): Result<Unit>

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
