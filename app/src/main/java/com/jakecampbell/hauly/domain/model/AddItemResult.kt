package com.jakecampbell.hauly.domain.model

/** Outcome of adding an item to the shopping list, for user feedback. */
enum class AddItemResult {
    /** A brand new item row was created. */
    CREATED,

    /** The item existed but was shopped; it was flipped back to unshopped. */
    REACTIVATED,

    /** The item is already on the active list; nothing changed. */
    ALREADY_ACTIVE,
}
