package com.jakecampbell.hauly.domain.usecase

import com.jakecampbell.hauly.domain.model.AddItemResult
import com.jakecampbell.hauly.domain.repository.ShoppingRepository
import javax.inject.Inject

/**
 * Adds an item to the shopping list, enforcing name uniqueness: an existing
 * row is reactivated instead of duplicated. New items are tagged with the
 * store view they were added from; existing items get that store appended.
 */
class AddItemToShoppingList @Inject constructor(
    private val repository: ShoppingRepository,
) {
    suspend operator fun invoke(
        name: String,
        quantity: Double? = null,
        store: String? = null,
    ): AddItemResult {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Item name must not be blank" }
        return repository.addItem(trimmed, quantity, store)
    }
}
