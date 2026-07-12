package com.jakecampbell.hauly.data.remote

import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Notion REST API. Responses are handled as [JsonObject] because Notion's
 * payloads are deeply polymorphic (per-property/per-block type unions);
 * [NotionMappers] extracts the small subset the app needs.
 */
interface NotionApi {

    @GET("databases/{id}")
    suspend fun getDatabase(@Path("id") databaseId: String): JsonObject

    /**
     * Variant used during onboarding: validates a candidate token by sending it
     * explicitly, before it has been persisted for the headers interceptor.
     */
    @GET("databases/{id}")
    suspend fun getDatabaseWithToken(
        @Path("id") databaseId: String,
        @Header("Authorization") bearerToken: String,
    ): JsonObject

    @GET("pages/{id}")
    suspend fun getPage(@Path("id") pageId: String): JsonObject

    @POST("databases/{id}/query")
    suspend fun queryDatabase(
        @Path("id") databaseId: String,
        @Body body: JsonObject,
    ): JsonObject

    @POST("pages")
    suspend fun createPage(@Body body: JsonObject): JsonObject

    @PATCH("pages/{id}")
    suspend fun updatePage(
        @Path("id") pageId: String,
        @Body body: JsonObject,
    ): JsonObject

    @GET("blocks/{id}/children")
    suspend fun getBlockChildren(
        @Path("id") blockId: String,
        @Query("start_cursor") startCursor: String? = null,
        @Query("page_size") pageSize: Int = 100,
    ): JsonObject
}
