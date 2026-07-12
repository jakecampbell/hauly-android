package com.jakecampbell.hauly.domain.model

/** Outcome of editing an item's properties from the edit dialog. */
enum class EditItemResult {
    SAVED,

    /** The new name is already used by a different item (names are unique). */
    DUPLICATE_NAME,
}
