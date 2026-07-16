package com.jakecampbell.hauly.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeExtractionDao {

    @Query("SELECT * FROM recipe_extractions ORDER BY created_at")
    fun extractions(): Flow<List<RecipeExtractionEntity>>

    /** Extractions the poll loop still needs to resolve. */
    @Query("SELECT * FROM recipe_extractions WHERE status IN ('PENDING', 'PROCESSING')")
    suspend fun active(): List<RecipeExtractionEntity>

    /** Rows whose submit POST is (or was) in flight; orphaned by a process death. */
    @Query("SELECT * FROM recipe_extractions WHERE status = 'SUBMITTING'")
    suspend fun submitting(): List<RecipeExtractionEntity>

    @Query("SELECT * FROM recipe_extractions WHERE id = :id")
    suspend fun byId(id: String): RecipeExtractionEntity?

    @Upsert
    suspend fun upsert(extraction: RecipeExtractionEntity)

    /**
     * Status transitions go through UPDATE (not upsert) so a row the user
     * cancelled mid-flight stays gone — updating a deleted id is a no-op.
     */
    @Query(
        "UPDATE recipe_extractions SET status = :status, title = :title, " +
            "ingredients = :ingredients, instructions = :instructions, " +
            "error = :error, updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun updateResult(
        id: String,
        status: String,
        title: String,
        ingredients: String,
        instructions: String,
        error: String?,
        updatedAt: Long,
    )

    /** Returns the number of rows removed — 0 when the user already dismissed it. */
    @Query("DELETE FROM recipe_extractions WHERE id = :id")
    suspend fun delete(id: String): Int
}
