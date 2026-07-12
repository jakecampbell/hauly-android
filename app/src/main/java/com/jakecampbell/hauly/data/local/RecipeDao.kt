package com.jakecampbell.hauly.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.jakecampbell.hauly.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeDao {

    @Query("SELECT * FROM recipes ORDER BY name COLLATE NOCASE")
    fun recipes(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes WHERE id = :id")
    fun recipe(id: String): Flow<RecipeEntity?>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun byId(id: String): RecipeEntity?

    @Query("SELECT * FROM recipes WHERE sync_status = 'PENDING_UPDATE'")
    suspend fun pendingRecipes(): List<RecipeEntity>

    @Upsert
    suspend fun upsert(recipe: RecipeEntity)

    @Upsert
    suspend fun upsertAll(recipes: List<RecipeEntity>)

    /**
     * Compare-and-set write-back for the sync engine: applies [recipe] only if
     * the row hasn't changed since the flush read it ([snapshotUpdatedAt]), so
     * a Planned toggle made while the push was in flight keeps its pending
     * status and wins over the stale snapshot.
     */
    @Transaction
    suspend fun upsertIfUnchanged(recipe: RecipeEntity, snapshotUpdatedAt: Long): Boolean {
        val current = byId(recipe.id) ?: return false
        if (current.updatedAt != snapshotUpdatedAt) return false
        upsert(recipe)
        return true
    }

    @Query("DELETE FROM recipes WHERE id NOT IN (:keepIds)")
    suspend fun deleteStale(keepIds: List<String>)

    /**
     * Replace the recipe list with the remote snapshot, preserving rows with a
     * queued Planned toggle and rows updated locally after [updatedBefore]
     * (Notion's query index lags just-accepted patches). ERROR rows outside
     * those guards are rolled back to remote truth.
     */
    @Transaction
    suspend fun applyRemoteSnapshot(recipes: List<RecipeEntity>, updatedBefore: Long) {
        if (recipes.isEmpty()) {
            deleteAll()
            return
        }
        deleteStale(recipes.map { it.id })
        for (incoming in recipes) {
            val existing = byId(incoming.id)
            if (existing != null &&
                (existing.syncStatus == SyncStatus.PENDING_UPDATE ||
                    existing.updatedAt >= updatedBefore)
            ) {
                continue
            }
            upsert(incoming)
        }
    }

    @Query("DELETE FROM recipes")
    suspend fun deleteAll()

    // --- Blocks ---

    @Query("SELECT * FROM recipe_blocks WHERE recipe_id = :recipeId ORDER BY order_index")
    fun blocks(recipeId: String): Flow<List<RecipeBlockEntity>>

    @Query("DELETE FROM recipe_blocks WHERE recipe_id = :recipeId")
    suspend fun deleteBlocks(recipeId: String)

    @Upsert
    suspend fun upsertBlocks(blocks: List<RecipeBlockEntity>)

    @Transaction
    suspend fun replaceBlocks(recipeId: String, blocks: List<RecipeBlockEntity>) {
        deleteBlocks(recipeId)
        upsertBlocks(blocks)
    }
}
