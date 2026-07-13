package com.jakecampbell.hauly.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Local-only "where am I" tracking for a recipe's ingredient/instruction lines:
 * a struck line is crossed out in the UI so the cook can keep their place. The
 * mere presence of a row means the line is struck. This state is **never synced
 * to Notion** — like `manual_rank` / `trip_shopped` on shopping items. Keyed by
 * line index within a section; an edit to that section clears its marks (line
 * positions shift). Cascade-deleted with the recipe.
 */
@Entity(
    tableName = "recipe_line_marks",
    primaryKeys = ["recipe_id", "section", "line_index"],
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
data class RecipeLineMarkEntity(
    @ColumnInfo(name = "recipe_id")
    val recipeId: String,

    /** [com.jakecampbell.hauly.domain.model.RecipeSection] name. */
    @ColumnInfo(name = "section")
    val section: String,

    @ColumnInfo(name = "line_index")
    val lineIndex: Int,
)
