package com.jakecampbell.hauly.domain.model

/** Lifecycle of a hauly-backend recipe extraction. */
enum class ExtractionStatus {
    /**
     * The POST is still in flight (the row has a client-generated id). Exists
     * so the UI shows activity immediately — a cold-started backend can hold
     * the submit request for tens of seconds.
     */
    SUBMITTING,
    PENDING,
    PROCESSING,
    COMPLETED,

    /**
     * The backend determined the text isn't a recipe at all. Terminal like
     * FAILED, but not retryable — resubmitting the same text can't help.
     */
    NO_RECIPE,
    FAILED;

    val isTerminal: Boolean get() = this == COMPLETED || this == NO_RECIPE || this == FAILED
}

/**
 * A recipe extraction job: submitted clipboard text being parsed (or already
 * parsed) into a recipe by the hauly-backend. Completed extractions hold the
 * parsed fields until the user reviews and creates the recipe, or dismisses.
 */
data class RecipeExtraction(
    val id: String,
    val status: ExtractionStatus,
    /** Extracted recipe name; "" until COMPLETED. */
    val title: String,
    /** Newline-separated ingredients; "" until COMPLETED. */
    val ingredients: String,
    /** Newline-separated instructions; "" until COMPLETED. */
    val instructions: String,
    /** Failure reason; null unless FAILED. */
    val error: String?,
    val createdAt: Long,
)
