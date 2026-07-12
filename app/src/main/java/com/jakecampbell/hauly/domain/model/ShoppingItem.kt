package com.jakecampbell.hauly.domain.model

/**
 * A row of the Notion "Shopping List" database.
 *
 * @param localId Stable app-local identity (survives create-sync).
 * @param remoteId Notion page id; null while a locally created item is waiting to sync.
 * @param recipeIds Notion page ids of related recipes (the `Recipes` relation).
 */
data class ShoppingItem(
    val localId: String,
    val remoteId: String?,
    val name: String,
    val stores: List<String>,
    val tags: List<String>,
    val quantity: Double?,
    val shopped: Boolean,
    val syncStatus: SyncStatus,
    val recipeIds: List<String> = emptyList(),
)
