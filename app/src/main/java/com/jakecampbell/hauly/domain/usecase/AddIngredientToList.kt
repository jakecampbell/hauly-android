package com.jakecampbell.hauly.domain.usecase

import com.jakecampbell.hauly.domain.model.AddItemResult
import com.jakecampbell.hauly.domain.repository.RecipeRepository
import javax.inject.Inject

/**
 * From a recipe screen: put an ingredient on the shopping list. New items are
 * created with the default "Grocery" store and linked to the recipe; existing
 * ones are toggled back to unshopped so they appear on the active list.
 */
class AddIngredientToList @Inject constructor(
    private val repository: RecipeRepository,
) {
    suspend operator fun invoke(
        recipeId: String,
        name: String,
        quantity: Double? = null,
    ): AddItemResult {
        val trimmed = name.trim().lowercase()
        require(trimmed.isNotEmpty()) { "Ingredient name must not be blank" }
        return repository.addIngredient(recipeId, trimmed, quantity)
    }
}
