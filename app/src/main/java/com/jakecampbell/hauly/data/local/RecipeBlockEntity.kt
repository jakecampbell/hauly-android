package com.jakecampbell.hauly.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Cached, read-only Notion page block belonging to a recipe. */
@Entity(
    tableName = "recipe_blocks",
    indices = [Index(value = ["recipe_id"])],
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipe_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RecipeBlockEntity(
    /** Notion block id. */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "recipe_id")
    val recipeId: String,

    @ColumnInfo(name = "order_index")
    val orderIndex: Int,

    /** Notion block type string, e.g. "paragraph", "heading_2". */
    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "checked")
    val checked: Boolean = false,
)
