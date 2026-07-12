package com.jakecampbell.hauly.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Local mirror of the Notion relation between recipes and shopping items.
 * Keyed on the item's *local* id so relations survive the create-sync of
 * locally added ingredients that don't have a Notion page id yet.
 */
@Entity(
    tableName = "recipe_item_refs",
    primaryKeys = ["recipe_id", "item_local_id"],
    indices = [Index(value = ["item_local_id"])],
)
data class RecipeItemCrossRef(
    @ColumnInfo(name = "recipe_id")
    val recipeId: String,

    @ColumnInfo(name = "item_local_id")
    val itemLocalId: String,
)
