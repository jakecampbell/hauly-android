package com.jakecampbell.hauly.domain.model

/**
 * Result of validating the user's Notion databases against the schemas the app
 * requires. [problems] is human-readable and names each missing/mismatched
 * property explicitly so the user can fix their Notion template.
 */
sealed interface SetupValidation {
    data object Valid : SetupValidation

    data class Invalid(val problems: List<SchemaProblem>) : SetupValidation

    data class Failed(val message: String) : SetupValidation
}

data class SchemaProblem(
    /** Which database the problem is in, e.g. "Shopping List". */
    val database: String,
    val property: String,
    /** Notion property type the app expects, e.g. "multi_select". */
    val expectedType: String,
    /** Actual type found, or null if the property is missing entirely. */
    val actualType: String?,
) {
    fun describe(): String =
        if (actualType == null) {
            "$database database is missing property \"$property\" (expected type: $expectedType)"
        } else {
            "$database property \"$property\" has type \"$actualType\" but must be \"$expectedType\""
        }
}
