package com.jakecampbell.hauly.domain.model

/**
 * A row of the Notion "Recipe" database. Name, ingredients, and instructions
 * are editable (offline-queued); Planned is the "make it" toggle.
 */
data class Recipe(
    val id: String,
    val name: String,
    /** Ingredient list text (newline-separated), from the `Ingredients` property. */
    val ingredients: String = "",
    /** Instruction text (newline-separated), from the `Instructions` property. */
    val instructions: String = "",
    /** Source link (Notion `URL` property); "" when unset. */
    val url: String = "",
    /** True when the user plans to make this recipe (Notion `Planned` checkbox). */
    val planned: Boolean = false,
    /** Notion's `last_edited_time` (epoch millis), for the "recent" sort. */
    val lastEditedAt: Long = 0L,
)

/** The two editable text sections of a recipe that support per-line strike tracking. */
enum class RecipeSection { INGREDIENTS, INSTRUCTIONS }

/** How the recipe list is ordered. Persisted locally; never synced to Notion. */
enum class RecipeSort {
    /** Alphabetical by name (the default). */
    ALPHA,

    /** Most recently edited in Notion first. */
    RECENT,
}
