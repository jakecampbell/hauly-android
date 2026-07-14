package com.jakecampbell.hauly.domain.usecase

import com.jakecampbell.hauly.domain.model.AddItemResult
import com.jakecampbell.hauly.domain.model.ShoppingItem
import com.jakecampbell.hauly.domain.repository.RecipeRepository
import javax.inject.Inject

/**
 * From a recipe screen: put an ingredient on the shopping list. New items are
 * created with the default "Grocery" store and linked to the recipe; existing
 * ones are linked and keep their current shopped state, so a shopped item stays
 * crossed out in the recipe's list rather than being pulled back onto the active
 * list. The picked add-dialog [suggestion] carries the matched item's shopped
 * state and recipe links, both of which seed a newly materialized row.
 */
class AddIngredientToList @Inject constructor(
    private val repository: RecipeRepository,
) {
    suspend operator fun invoke(
        recipeId: String,
        name: String,
        quantity: Double? = null,
        suggestion: ShoppingItem? = null,
    ): AddItemResult {
        val trimmed = name.trim().lowercase()
        require(trimmed.isNotEmpty()) { "Ingredient name must not be blank" }
        return repository.addIngredient(recipeId, trimmed, quantity, suggestion)
    }
}
