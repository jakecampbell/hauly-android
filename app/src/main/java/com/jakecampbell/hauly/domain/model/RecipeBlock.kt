package com.jakecampbell.hauly.domain.model

/** Rendered subset of a Notion page block, for read-only recipe instructions. */
data class RecipeBlock(
    val id: String,
    val type: BlockType,
    val text: String,
    /** Only meaningful for [BlockType.TODO]. */
    val checked: Boolean = false,
    /** Position of a numbered list item within its run, 1-based. */
    val ordinal: Int = 0,
)

enum class BlockType {
    PARAGRAPH,
    HEADING_1,
    HEADING_2,
    HEADING_3,
    BULLETED_LIST_ITEM,
    NUMBERED_LIST_ITEM,
    TODO,
    QUOTE,
    CALLOUT,
    DIVIDER,
    /** Any block type the app doesn't render specially; shown as plain text if it has any. */
    UNSUPPORTED,
}
