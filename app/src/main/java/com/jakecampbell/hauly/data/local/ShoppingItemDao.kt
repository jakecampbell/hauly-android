package com.jakecampbell.hauly.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.jakecampbell.hauly.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface ShoppingItemDao {

    /** Manually placed items first (in drag order), the rest alphabetical. */
    @Query(
        """
        SELECT * FROM shopping_items
        WHERE shopped = 0 AND sync_status != 'PENDING_DELETE'
        ORDER BY manual_rank IS NULL, manual_rank, name COLLATE NOCASE
        """
    )
    fun activeItems(): Flow<List<ShoppingItemEntity>>

    /** Items checked off during the current trip, in the order they were shopped. */
    @Query(
        """
        SELECT * FROM shopping_items
        WHERE trip_shopped = 1 AND sync_status != 'PENDING_DELETE'
        ORDER BY updated_at
        """
    )
    fun tripItems(): Flow<List<ShoppingItemEntity>>

    @Query(
        """
        DELETE FROM shopping_items
        WHERE trip_shopped = 1 AND sync_status IN ('SYNCED', 'ERROR')
          AND local_id NOT IN (SELECT item_local_id FROM recipe_item_refs)
        """
    )
    suspend fun deleteSyncedTripRows()

    @Query("UPDATE shopping_items SET trip_shopped = 0 WHERE trip_shopped = 1")
    suspend fun clearTripFlags()

    /**
     * End the shopping trip: discard local tracking of shopped items. Synced
     * rows are evicted unless a recipe links them (the cache keeps recipe
     * ingredients, shopped or not); rows still waiting to sync just lose the
     * trip flag and are evicted after their flush.
     */
    @Transaction
    suspend fun finishTrip() {
        deleteSyncedTripRows()
        clearTripFlags()
    }

    /**
     * Local-only write: manual sort position is never synced to Notion, so it
     * deliberately bypasses sync_status.
     */
    @Query("UPDATE shopping_items SET manual_rank = :rank WHERE local_id = :localId")
    suspend fun setManualRank(localId: String, rank: Int?)

    @Transaction
    suspend fun setManualOrder(orderedLocalIds: List<String>) {
        orderedLocalIds.forEachIndexed { index, localId -> setManualRank(localId, index) }
    }

    @Query("SELECT COUNT(*) FROM shopping_items WHERE sync_status IN ('PENDING_CREATE', 'PENDING_UPDATE', 'PENDING_DELETE')")
    fun pendingCount(): Flow<Int>

    @Query("SELECT * FROM shopping_items WHERE sync_status IN ('PENDING_CREATE', 'PENDING_UPDATE', 'PENDING_DELETE')")
    suspend fun pendingItems(): List<ShoppingItemEntity>

    @Query("SELECT * FROM shopping_items WHERE local_id = :localId")
    suspend fun byLocalId(localId: String): ShoppingItemEntity?

    @Query("SELECT * FROM shopping_items WHERE remote_id = :remoteId")
    suspend fun byRemoteId(remoteId: String): ShoppingItemEntity?

    @Query("SELECT * FROM shopping_items WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): ShoppingItemEntity?

    /** Type-ahead matches for the add-item dialog (cached rows only). */
    @Query(
        """
        SELECT * FROM shopping_items
        WHERE name LIKE '%' || :query || '%' AND sync_status != 'PENDING_DELETE'
        ORDER BY name COLLATE NOCASE
        LIMIT 10
        """
    )
    fun matching(query: String): Flow<List<ShoppingItemEntity>>

    @Upsert
    suspend fun upsert(item: ShoppingItemEntity)

    @Upsert
    suspend fun upsertAll(items: List<ShoppingItemEntity>)

    /**
     * Compare-and-set write-back for the sync engine: applies [item] only if
     * the row hasn't changed since the flush read it ([snapshotUpdatedAt]).
     * Every user-facing write bumps `updated_at`, so a mismatch means the user
     * edited the row while its network call was in flight — that edit keeps
     * its PENDING_* status and newer values instead of being clobbered by the
     * stale snapshot.
     *
     * @return true if the row was written; false if it changed or is gone.
     */
    @Transaction
    suspend fun upsertIfUnchanged(item: ShoppingItemEntity, snapshotUpdatedAt: Long): Boolean {
        val current = byLocalId(item.localId) ?: return false
        if (current.updatedAt != snapshotUpdatedAt) return false
        upsert(item)
        return true
    }

    /**
     * Fallback when a create lands mid-edit ([upsertIfUnchanged] refused the
     * SYNCED write-back): record the page id Notion just assigned — so the next
     * flush updates that page instead of re-creating it (Notion's query index
     * lags, so re-running create-with-merge could duplicate) — and flip
     * PENDING_CREATE to PENDING_UPDATE while keeping the user's newer values.
     * OR IGNORE guards the unique remote_id index against a concurrent refresh
     * having cached the same page under another row.
     */
    @Query(
        """
        UPDATE OR IGNORE shopping_items
        SET remote_id = :remoteId,
            -- The row now has a page id, so any assumed-shopped placeholder is
            -- spent (the flag is only read while PENDING_CREATE). Hygiene.
            shopped_assumed = 0,
            sync_status = CASE WHEN sync_status = 'PENDING_CREATE'
                               THEN 'PENDING_UPDATE' ELSE sync_status END
        WHERE local_id = :localId AND remote_id IS NULL
        """
    )
    suspend fun attachRemoteId(localId: String, remoteId: String)

    /**
     * Complete a flushed delete, unless the row was resurrected (re-added under
     * the same name) while the archive call was in flight — a resurrected row
     * is PENDING_UPDATE again and must survive to be pushed.
     */
    @Transaction
    suspend fun deleteIfStillPendingDelete(localId: String) {
        val current = byLocalId(localId) ?: return
        if (current.syncStatus != SyncStatus.PENDING_DELETE) return
        deleteRefsForItem(localId)
        delete(localId)
    }

    @Query("DELETE FROM shopping_items WHERE local_id = :localId")
    suspend fun delete(localId: String)

    @Query(
        """
        DELETE FROM shopping_items
        WHERE sync_status IN ('SYNCED', 'ERROR')
          AND trip_shopped = 0
          AND updated_at < :updatedBefore
          AND (remote_id IS NULL OR remote_id NOT IN (:keepRemoteIds))
        """
    )
    suspend fun deleteStale(keepRemoteIds: List<String>, updatedBefore: Long)

    // --- Relations ---

    @Query("SELECT * FROM recipe_item_refs WHERE item_local_id = :itemLocalId")
    suspend fun refsForItem(itemLocalId: String): List<RecipeItemCrossRef>

    @Query("DELETE FROM recipe_item_refs WHERE item_local_id = :itemLocalId")
    suspend fun deleteRefsForItem(itemLocalId: String)

    /** Unlink one item from one recipe, leaving its other recipe links intact. */
    @Query("DELETE FROM recipe_item_refs WHERE recipe_id = :recipeId AND item_local_id = :itemLocalId")
    suspend fun deleteRef(recipeId: String, itemLocalId: String)

    @Upsert
    suspend fun upsertRefs(refs: List<RecipeItemCrossRef>)

    @Query(
        """
        SELECT si.* FROM shopping_items si
        INNER JOIN recipe_item_refs r ON si.local_id = r.item_local_id
        WHERE r.recipe_id = :recipeId AND si.sync_status != 'PENDING_DELETE'
        ORDER BY si.name COLLATE NOCASE
        """
    )
    fun itemsForRecipe(recipeId: String): Flow<List<ShoppingItemEntity>>

    @Query("SELECT * FROM recipe_item_refs WHERE recipe_id = :recipeId AND item_local_id = :itemLocalId")
    suspend fun ref(recipeId: String, itemLocalId: String): RecipeItemCrossRef?

    /**
     * Replace the cache with the remote snapshot (active items plus
     * recipe-linked ones) without touching rows that have queued offline edits
     * (PENDING_*). ERROR rows are rolled back to remote truth here, and
     * SYNCED/ERROR rows that disappeared from the remote snapshot are evicted.
     *
     * @param remote pairs of (entity built from remote, related recipe page ids)
     */
    @Transaction
    suspend fun applyRemoteSnapshot(
        remote: List<Pair<ShoppingItemEntity, List<String>>>,
        updatedBefore: Long,
    ) {
        val keepRemoteIds = remote.map { it.first.remoteId!! }
        deleteStale(keepRemoteIds, updatedBefore)
        for ((incoming, recipeIds) in remote) {
            val existing = byRemoteId(incoming.remoteId!!) ?: byName(incoming.name)
            if (existing != null &&
                (existing.syncStatus == SyncStatus.PENDING_CREATE ||
                    existing.syncStatus == SyncStatus.PENDING_UPDATE ||
                    existing.syncStatus == SyncStatus.PENDING_DELETE ||
                    // Trip rows are local-owned until "Done", and rows updated
                    // moments ago can be misreported by Notion's query index,
                    // which lags behind patches it just accepted.
                    existing.tripShopped ||
                    existing.updatedAt >= updatedBefore)
            ) {
                // Never clobber queued or freshly-synced local edits.
                continue
            }
            val localId = existing?.localId ?: UUID.randomUUID().toString()
            // Manual sort position is local-only state; a refresh must not reset it.
            upsert(incoming.copy(localId = localId, manualRank = existing?.manualRank))
            deleteRefsForItem(localId)
            upsertRefs(recipeIds.map { RecipeItemCrossRef(it, localId) })
        }
    }
}
