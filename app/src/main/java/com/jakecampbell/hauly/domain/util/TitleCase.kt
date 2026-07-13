package com.jakecampbell.hauly.domain.util

/** Capitalizes the first letter of each word; used to normalize store names before they are saved. */
fun titleCase(text: String): String =
    text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        .joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }

/**
 * Same capitalization as [titleCase] but preserves the input's exact spacing,
 * including a trailing space between words. Safe to apply on every keystroke
 * of a live text field — [titleCase] would eat a trailing space the moment
 * it's typed, making it impossible to start a second word.
 */
fun titleCaseWhileTyping(text: String): String =
    text.split(" ").joinToString(" ") { word ->
        if (word.isEmpty()) word else word.lowercase().replaceFirstChar(Char::uppercase)
    }
