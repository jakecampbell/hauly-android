package com.jakecampbell.hauly.data.remote

/** Property names and types the app requires in the user's Notion databases. */
object NotionSchema {
    const val SHOPPING_DB_LABEL = "Shopping List"
    const val RECIPE_DB_LABEL = "Recipe"

    const val PROP_NAME = "Name"
    const val PROP_STORE = "Store"
    const val PROP_TAG = "Tags"
    const val PROP_QUANTITY = "Qty"
    const val PROP_SHOPPED = "Shopped"
    const val PROP_RECIPES = "Recipes"
    const val PROP_SHOPPING = "Shopping"
    const val PROP_PLANNED = "Planned"
    const val PROP_INGREDIENTS = "Ingredients"
    const val PROP_INSTRUCTIONS = "Instructions"
    const val PROP_URL = "URL"

    /** property name -> expected Notion type */
    val shoppingListProperties = mapOf(
        PROP_NAME to "title",
        PROP_STORE to "multi_select",
        PROP_TAG to "multi_select",
        PROP_QUANTITY to "number",
        PROP_SHOPPED to "checkbox",
        PROP_RECIPES to "relation",
    )

    val recipeProperties = mapOf(
        PROP_NAME to "title",
        PROP_SHOPPING to "relation",
        PROP_PLANNED to "checkbox",
        PROP_INGREDIENTS to "rich_text",
        PROP_INSTRUCTIONS to "rich_text",
        PROP_URL to "url",
    )
}
