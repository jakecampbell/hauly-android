package com.jakecampbell.hauly.data.remote.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * The hauly-backend recipe-extraction service (R2.10). Unlike Notion, this API
 * is a contract we own, so responses are typed DTOs rather than lenient JSON.
 * Absent-when-null fields rely on the shared Json's `explicitNulls = false`.
 */
@Serializable
data class ExtractRequest(val content: String)

@Serializable
data class ExtractSubmitResponse(
    @SerialName("extraction_id") val extractionId: String,
    val status: String,
)

/** Extracted recipe; ingredients/instructions are newline-separated strings. */
@Serializable
data class ExtractedRecipeDto(
    val title: String = "",
    val ingredients: String = "",
    val instructions: String = "",
)

@Serializable
data class ExtractionStatusResponse(
    @SerialName("extraction_id") val extractionId: String,
    /** pending | processing | completed | failed */
    val status: String,
    val recipe: ExtractedRecipeDto? = null,
    val error: String? = null,
)

interface HaulyBackendApi {

    @POST("api/v1/recipes/extract")
    suspend fun submitExtraction(@Body body: ExtractRequest): ExtractSubmitResponse

    /**
     * The "magic" extractor: same contract as [submitExtraction] (202 + id, then
     * polled via [extractionStatus]) but the backend builds the recipe from a
     * free-text blob the user typed rather than pasted source (R8.15).
     */
    @POST("api/v1/recipes/extract/magic")
    suspend fun submitMagicExtraction(@Body body: ExtractRequest): ExtractSubmitResponse

    @GET("api/v1/recipes/extractions/{id}")
    suspend fun extractionStatus(@Path("id") id: String): ExtractionStatusResponse
}
