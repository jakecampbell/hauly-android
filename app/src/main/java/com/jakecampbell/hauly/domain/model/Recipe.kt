package com.jakecampbell.hauly.domain.model

/**
 * A row of the Notion "Recipe" database. Content is read-only in the app;
 * only the Planned flag can be toggled.
 */
data class Recipe(
    val id: String,
    val name: String,
    /** True when the user plans to make this recipe (Notion `Planned` checkbox). */
    val planned: Boolean = false,
    /** Notion's `last_edited_time` (epoch millis), for the "recent" sort. */
    val lastEditedAt: Long = 0L,
)
