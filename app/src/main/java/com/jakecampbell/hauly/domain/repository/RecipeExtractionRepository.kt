package com.jakecampbell.hauly.domain.repository

import com.jakecampbell.hauly.domain.model.RecipeExtraction
import kotlinx.coroutines.flow.Flow

interface RecipeExtractionRepository {

    /** All extraction rows (in-flight and terminal), oldest first. */
    fun extractions(): Flow<List<RecipeExtraction>>

    /**
     * Submit text to the backend. Fire-and-forget: a SUBMITTING row appears
     * immediately (so the UI shows activity even while a cold-started backend
     * holds the POST), upgrades to PENDING once the server assigns an id, and
     * becomes FAILED (with Retry available) if the submit can't get through.
     * Runs in the app scope so leaving the screen doesn't cancel the POST.
     * [magic] routes free text to the backend's magic extractor (R8.15); the
     * default is the pasted-source `extract` route.
     */
    fun submit(text: String, magic: Boolean = false)

    /** Resubmit a failed extraction's stored source text as a new job. */
    fun retry(id: String)

    /** Remove an extraction row without creating a recipe. */
    suspend fun dismiss(id: String)

    /** Resume polling any unfinished extractions (call on app start). */
    fun resumePolling()
}
