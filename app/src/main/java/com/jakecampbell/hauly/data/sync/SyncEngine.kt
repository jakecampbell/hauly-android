package com.jakecampbell.hauly.data.sync

import com.jakecampbell.hauly.data.local.RecipeDao
import com.jakecampbell.hauly.data.local.RecipeEntity
import com.jakecampbell.hauly.data.local.RecipeItemCrossRef
import com.jakecampbell.hauly.data.local.ShoppingItemDao
import com.jakecampbell.hauly.data.local.ShoppingItemEntity
import com.jakecampbell.hauly.data.remote.NotionRemoteDataSource
import com.jakecampbell.hauly.data.remote.NotionSchema
import com.jakecampbell.hauly.data.remote.NotionMappers
import com.jakecampbell.hauly.data.repository.toEntity
import com.jakecampbell.hauly.data.settings.SettingsRepository
import com.jakecampbell.hauly.domain.model.SyncStatus
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single place that talks Notion for cache purposes: flushing the offline
 * queue and refreshing the local cache to remote truth. Used by both the
 * WorkManager sync job and user-triggered pull-to-refresh.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val itemDao: ShoppingItemDao,
    private val recipeDao: RecipeDao,
    private val remote: NotionRemoteDataSource,
    private val settings: SettingsRepository,
) {

    enum class FlushOutcome { SUCCESS, RETRY }

    /**
     * Push every PENDING_CREATE / PENDING_UPDATE row to Notion.
     *
     * Permanent failures (HTTP 4xx) mark the row ERROR so the next refresh
     * rolls it back to remote truth. Transient failures (network, exhausted
     * rate-limit retries) leave the queue intact and request a WorkManager retry.
     */
    suspend fun flushQueue(): FlushOutcome {
        val itemsOutcome = flushItemQueue()
        val recipesOutcome = flushRecipeQueue()
        return if (itemsOutcome == FlushOutcome.RETRY || recipesOutcome == FlushOutcome.RETRY) {
            FlushOutcome.RETRY
        } else {
            FlushOutcome.SUCCESS
        }
    }

    private suspend fun flushItemQueue(): FlushOutcome {
        val databaseId = settings.shoppingDatabaseId() ?: return FlushOutcome.SUCCESS
        for (queued in itemDao.pendingItems()) {
            // Re-read per row: the user may have edited it since listing the
            // queue. This snapshot's updatedAt then guards every write-back
            // below, so an edit made while the network call is in flight is
            // never clobbered — it keeps its PENDING_* status and the appended
            // sync run pushes it.
            val item = itemDao.byLocalId(queued.localId) ?: continue
            val recipeIds = itemDao.refsForItem(item.localId).map { it.recipeId }
            try {
                when (item.syncStatus) {
                    SyncStatus.PENDING_CREATE -> pushCreate(databaseId, item, recipeIds)
                    SyncStatus.PENDING_UPDATE -> pushUpdate(item, recipeIds)
                    SyncStatus.PENDING_DELETE -> pushDelete(item)
                    else -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code() in 400..499) {
                    // Guarded: an edit made during the failed request (e.g. a
                    // rename fixing a 400) stays queued instead of erroring.
                    itemDao.upsertIfUnchanged(
                        item.copy(syncStatus = SyncStatus.ERROR),
                        item.updatedAt,
                    )
                } else {
                    return FlushOutcome.RETRY
                }
            } catch (e: IOException) {
                return FlushOutcome.RETRY
            }
        }
        return FlushOutcome.SUCCESS
    }

    /**
     * Push queued recipe edits (name / ingredients / instructions / Planned) to
     * the recipe database. Whole-recipe create and delete are online-first, so
     * the queue only ever holds PENDING_UPDATE rows.
     */
    private suspend fun flushRecipeQueue(): FlushOutcome {
        for (queued in recipeDao.pendingRecipes()) {
            val recipe = recipeDao.byId(queued.id) ?: continue
            if (recipe.syncStatus != SyncStatus.PENDING_UPDATE) continue
            try {
                remote.updateRecipe(
                    pageId = recipe.id,
                    name = recipe.name,
                    ingredients = recipe.ingredients,
                    instructions = recipe.instructions,
                    url = recipe.url,
                    planned = recipe.planned,
                )
                // Guarded: a toggle made while the push was in flight stays
                // PENDING_UPDATE and is flushed by the appended sync run.
                recipeDao.upsertIfUnchanged(
                    recipe.copy(
                        syncStatus = SyncStatus.SYNCED,
                        updatedAt = System.currentTimeMillis(),
                    ),
                    recipe.updatedAt,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code() in 400..499) {
                    recipeDao.upsertIfUnchanged(
                        recipe.copy(syncStatus = SyncStatus.ERROR),
                        recipe.updatedAt,
                    )
                } else {
                    return FlushOutcome.RETRY
                }
            } catch (e: IOException) {
                return FlushOutcome.RETRY
            }
        }
        return FlushOutcome.SUCCESS
    }

    private suspend fun pushCreate(
        databaseId: String,
        item: ShoppingItemEntity,
        recipeIds: List<String>,
    ) {
        val existingRemote = remote.findItemByName(databaseId, item.name)
        val now = System.currentTimeMillis()

        if (existingRemote != null) {
            // Duplicate prevention: the name already exists in Notion. Reactivate
            // it, merging local edits from the add dialog (store tag, quantity)
            // into the remote row instead of duplicating it.
            val mergedRecipes = (existingRemote.recipeIds + recipeIds).distinct()
            val mergedStores = (existingRemote.stores + item.stores).distinctBy { it.lowercase() }
            val mergedTags = (existingRemote.tags + item.tags).distinctBy { it.lowercase() }
            val quantity = item.quantity ?: existingRemote.quantity
            // A `shoppedAssumed` row never asserted its Shopped value (a recipe
            // add with no suggestion): adopt the existing page's state instead of
            // un-shopping it. Every other row pushes the value it asserted (R7.11).
            val shopped = if (item.shoppedAssumed) existingRemote.shopped else item.shopped
            remote.updateItem(
                pageId = existingRemote.pageId,
                name = existingRemote.name,
                stores = mergedStores,
                tags = mergedTags,
                quantity = quantity,
                shopped = shopped,
                recipeIds = mergedRecipes,
            )
            val wroteBack = itemDao.upsertIfUnchanged(
                item.copy(
                    remoteId = existingRemote.pageId,
                    name = existingRemote.name,
                    stores = mergedStores,
                    tags = mergedTags,
                    quantity = quantity,
                    // Converge on the page's real state; the assumption is resolved.
                    shopped = shopped,
                    shoppedAssumed = false,
                    syncStatus = SyncStatus.SYNCED,
                    updatedAt = now,
                ),
                item.updatedAt,
            )
            if (!wroteBack) itemDao.attachRemoteId(item.localId, existingRemote.pageId)
            // R5.10: the relation we just pushed is the page's complete relation,
            // but the merge pulled in recipe links the local ref set didn't have.
            // Write the union back so a later PENDING_UPDATE push doesn't drop
            // them. Guard on the cheap set diff first (keeps the byLocalId read
            // off the common path), and only if the row still exists — a
            // mid-flight delete hard-removes the row and its refs, and re-adding
            // them would orphan them.
            if (mergedRecipes.toSet() != recipeIds.toSet() &&
                itemDao.byLocalId(item.localId) != null
            ) {
                itemDao.upsertRefs(mergedRecipes.map { RecipeItemCrossRef(it, item.localId) })
            }
        } else {
            val created = remote.createItem(
                databaseId = databaseId,
                name = item.name,
                stores = item.stores,
                tags = item.tags,
                quantity = item.quantity,
                shopped = item.shopped,
                recipeIds = recipeIds,
            )
            val wroteBack = itemDao.upsertIfUnchanged(
                item.copy(
                    remoteId = created?.pageId,
                    // No page existed, so the assumed unshopped state was right.
                    shoppedAssumed = false,
                    syncStatus = if (created != null) SyncStatus.SYNCED else SyncStatus.ERROR,
                    updatedAt = now,
                ),
                item.updatedAt,
            )
            if (!wroteBack && created != null) {
                if (itemDao.byLocalId(item.localId) == null) {
                    // Deleted locally while the create was in flight (rows
                    // without a remoteId are hard-deleted): honor the delete by
                    // archiving the page we just created.
                    remote.archiveItem(created.pageId)
                } else {
                    itemDao.attachRemoteId(item.localId, created.pageId)
                }
            }
        }
    }

    private suspend fun pushUpdate(item: ShoppingItemEntity, recipeIds: List<String>) {
        val remoteId = item.remoteId
        if (remoteId == null) {
            itemDao.upsertIfUnchanged(item.copy(syncStatus = SyncStatus.ERROR), item.updatedAt)
            return
        }
        remote.updateItem(
            pageId = remoteId,
            name = item.name,
            stores = item.stores,
            tags = item.tags,
            quantity = item.quantity,
            shopped = item.shopped,
            recipeIds = recipeIds,
        )
        itemDao.upsertIfUnchanged(
            item.copy(syncStatus = SyncStatus.SYNCED, updatedAt = System.currentTimeMillis()),
            item.updatedAt,
        )
    }

    /**
     * Archive the Notion page, then remove the local row and its relations —
     * unless the row was resurrected (re-added) while the archive call was in
     * flight. A resurrected row is PENDING_UPDATE again, and its push
     * un-archives the page (item updates always send `archived: false`).
     */
    private suspend fun pushDelete(item: ShoppingItemEntity) {
        item.remoteId?.let { remote.archiveItem(it) }
        itemDao.deleteIfStillPendingDelete(item.localId)
    }

    /**
     * Pull remote truth into the cache: refresh store/tag options from the
     * database schema and replace the cached-item snapshot (active items plus
     * recipe-linked ones, shopped or not). Rows with queued edits are
     * preserved; ERROR rows are rolled back to remote state.
     */
    suspend fun refreshShopping(): Result<Unit> = runCatchingNotCancelled {
        val databaseId = settings.shoppingDatabaseId()
            ?: error("Notion is not configured yet")

        val database = remote.getDatabase(databaseId)
        settings.saveSelectOptions(
            stores = NotionMappers.schemaSelectOptions(database, NotionSchema.PROP_STORE),
            tags = NotionMappers.schemaSelectOptions(database, NotionSchema.PROP_TAG),
        )

        val now = System.currentTimeMillis()
        val snapshot = remote.fetchCachedItems(databaseId).map { remoteItem ->
            remoteItem.toEntity(UUID.randomUUID().toString(), now) to remoteItem.recipeIds
        }
        itemDao.applyRemoteSnapshot(snapshot, updatedBefore = now - REMOTE_LAG_GRACE_MILLIS)
        settings.markSynced(now)
    }

    /** Refresh the recipe list and (for cached items) recipe→item relations. */
    suspend fun refreshRecipes(): Result<Unit> = runCatchingNotCancelled {
        val databaseId = settings.recipeDatabaseId()
            ?: error("Notion is not configured yet")

        val now = System.currentTimeMillis()
        val remoteRecipes = remote.fetchRecipes(databaseId)
        recipeDao.applyRemoteSnapshot(
            remoteRecipes.map {
                RecipeEntity(
                    id = it.pageId,
                    name = it.name,
                    ingredients = it.ingredients,
                    instructions = it.instructions,
                    url = it.url,
                    planned = it.planned,
                    lastEditedAt = it.lastEditedAt,
                    updatedAt = now,
                )
            },
            updatedBefore = now - REMOTE_LAG_GRACE_MILLIS,
        )
        // Link relations from the recipe side for items already in the cache.
        for (recipe in remoteRecipes) {
            val refs = recipe.itemPageIds.mapNotNull { pageId ->
                itemDao.byRemoteId(pageId)?.let { RecipeItemCrossRef(recipe.pageId, it.localId) }
            }
            if (refs.isNotEmpty()) itemDao.upsertRefs(refs)
        }
    }

    private suspend fun <T> runCatchingNotCancelled(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    companion object {
        /**
         * Rows updated locally within this window are protected from remote
         * snapshots: Notion's query index lags behind patches it just accepted,
         * so a refresh right after a flush can report pre-flush state.
         */
        private const val REMOTE_LAG_GRACE_MILLIS = 60_000L
    }
}
