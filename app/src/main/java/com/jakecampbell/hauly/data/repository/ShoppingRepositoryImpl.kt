package com.jakecampbell.hauly.data.repository

import com.jakecampbell.hauly.data.local.RecipeItemCrossRef
import com.jakecampbell.hauly.data.local.ShoppingItemDao
import com.jakecampbell.hauly.data.local.ShoppingItemEntity
import com.jakecampbell.hauly.data.remote.NotionRemoteDataSource
import com.jakecampbell.hauly.data.settings.SettingsRepository
import com.jakecampbell.hauly.data.sync.SyncEngine
import com.jakecampbell.hauly.data.sync.SyncScheduler
import com.jakecampbell.hauly.domain.model.AddItemResult
import com.jakecampbell.hauly.domain.model.EditItemResult
import com.jakecampbell.hauly.domain.model.ShoppedHistoryPage
import com.jakecampbell.hauly.domain.model.ShoppingItem
import com.jakecampbell.hauly.domain.model.SyncStatus
import com.jakecampbell.hauly.domain.repository.ShoppingRepository
import com.jakecampbell.hauly.domain.util.titleCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShoppingRepositoryImpl @Inject constructor(
    private val dao: ShoppingItemDao,
    private val remote: NotionRemoteDataSource,
    private val settings: SettingsRepository,
    private val syncEngine: SyncEngine,
    private val syncScheduler: SyncScheduler,
) : ShoppingRepository {

    override fun activeItems(): Flow<List<ShoppingItem>> =
        dao.activeItems().map { list -> list.map { it.toDomain() } }

    override fun tripItems(): Flow<List<ShoppingItem>> =
        dao.tripItems().map { list -> list.map { it.toDomain() } }

    override suspend fun finishTrip() = dao.finishTrip()

    override fun storeOptions(): Flow<List<String>> =
        combine(
            settings.storeOptions,
            dao.activeItems(),
            settings.storeManualOrder,
        ) { schema, items, manualOrder ->
            val known = (schema + items.flatMap { it.stores }).distinctBy { it.lowercase() }
            val placed = manualOrder.filter { placed -> known.any { it.equals(placed, ignoreCase = true) } }
            val unplaced = known.filter { store -> placed.none { it.equals(store, ignoreCase = true) } }
            placed + unplaced
        }

    override suspend fun setManualOrder(orderedLocalIds: List<String>) {
        dao.setManualOrder(orderedLocalIds)
    }

    override suspend fun setStoreOrder(order: List<String>) {
        settings.setStoreManualOrder(order)
    }

    override fun tagOptions(): Flow<List<String>> = settings.tagOptions

    override fun groupByTags(): Flow<Boolean> = settings.groupByTags

    override suspend fun setGroupByTags(enabled: Boolean) = settings.setGroupByTags(enabled)

    override fun pendingCount(): Flow<Int> = dao.pendingCount()

    override suspend fun refresh(): Result<Unit> = syncEngine.refreshShopping()

    override fun matchingItems(query: String): Flow<List<ShoppingItem>> =
        dao.matching(query).map { list -> list.map { it.toDomain() } }

    override suspend fun addItem(name: String, quantity: Double?, store: String?): AddItemResult {
        val now = System.currentTimeMillis()
        val existing = dao.byName(name)

        if (existing == null) {
            dao.upsert(
                ShoppingItemEntity(
                    localId = UUID.randomUUID().toString(),
                    remoteId = null,
                    name = name,
                    stores = listOfNotNull(store),
                    tags = emptyList(),
                    quantity = quantity,
                    shopped = false,
                    syncStatus = SyncStatus.PENDING_CREATE,
                    updatedAt = now,
                )
            )
            syncScheduler.requestSync()
            return AddItemResult.CREATED
        }

        val updated = existing.copy(
            shopped = false,
            tripShopped = false,
            // The user asserted this item onto the active list — clear any assumption.
            shoppedAssumed = false,
            stores = withStore(existing.stores, store),
            quantity = quantity ?: existing.quantity,
        )
        if (updated != existing) {
            dao.upsert(updated.copy(syncStatus = pendingFor(existing), updatedAt = now))
            syncScheduler.requestSync()
        }
        return if (existing.shopped) AddItemResult.REACTIVATED else AddItemResult.ALREADY_ACTIVE
    }

    override suspend fun setShopped(localId: String, shopped: Boolean) {
        val item = dao.byLocalId(localId) ?: return
        val now = System.currentTimeMillis()
        dao.upsert(
            item.copy(
                shopped = shopped,
                // Checking off adds the item to the local trip ledger;
                // un-shopping (from the ledger or elsewhere) removes it.
                tripShopped = shopped,
                // Manual drag position only holds until the item is shopped.
                manualRank = if (shopped) null else item.manualRank,
                // An explicit shopped write is an assertion; it beats any assumption.
                shoppedAssumed = false,
                syncStatus = pendingFor(item),
                updatedAt = now,
            )
        )
        if (shopped) settings.recordStoreShopped(item.stores, now)
        syncScheduler.requestSync()
    }

    override suspend fun discard(localId: String) {
        val item = dao.byLocalId(localId) ?: return
        dao.upsert(
            item.copy(
                shopped = true,
                // The whole point: shopped, but never in the trip ledger.
                tripShopped = false,
                manualRank = null,
                shoppedAssumed = false,
                syncStatus = pendingFor(item),
                updatedAt = System.currentTimeMillis(),
            )
        )
        syncScheduler.requestSync()
    }

    override suspend fun deleteItem(localId: String) {
        val item = dao.byLocalId(localId) ?: return
        if (item.remoteId == null) {
            // Never reached Notion: nothing to archive, drop the row outright.
            dao.deleteRefsForItem(localId)
            dao.delete(localId)
        } else {
            dao.upsert(
                item.copy(
                    syncStatus = SyncStatus.PENDING_DELETE,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            syncScheduler.requestSync()
        }
    }

    override suspend fun updateDetails(
        localId: String,
        name: String,
        stores: List<String>,
        tags: List<String>,
        quantity: Double?,
    ): EditItemResult {
        val item = dao.byLocalId(localId) ?: return EditItemResult.SAVED
        val normalizedName = name.trim().lowercase()
        // Names are unique (case-insensitive); renaming onto another item
        // would violate the index and silently merge two Notion pages.
        val clash = dao.byName(normalizedName)
        if (clash != null && clash.localId != item.localId) return EditItemResult.DUPLICATE_NAME

        val updated = item.copy(
            name = normalizedName,
            stores = stores.map(::titleCase),
            tags = normalizeTags(tags),
            quantity = quantity,
        )
        if (updated != item) {
            dao.upsert(updated.copy(syncStatus = pendingFor(item), updatedAt = System.currentTimeMillis()))
            syncScheduler.requestSync()
        }
        return EditItemResult.SAVED
    }

    override suspend fun assignStores(localId: String, stores: List<String>) {
        val item = dao.byLocalId(localId) ?: return
        dao.upsert(
            item.copy(
                stores = stores.map(::titleCase),
                syncStatus = pendingFor(item),
                updatedAt = System.currentTimeMillis(),
            )
        )
        syncScheduler.requestSync()
    }

    override suspend fun assignTags(localId: String, tags: List<String>) {
        val item = dao.byLocalId(localId) ?: return
        dao.upsert(
            item.copy(
                tags = normalizeTags(tags),
                syncStatus = pendingFor(item),
                updatedAt = System.currentTimeMillis(),
            )
        )
        syncScheduler.requestSync()
    }

    override suspend fun searchRemote(query: String): Result<List<ShoppingItem>> =
        try {
            val databaseId = settings.shoppingDatabaseId()
                ?: error("Notion is not configured yet")
            Result.success(remote.searchItems(databaseId, query).map { it.toDomain() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    override suspend fun shoppedHistory(
        store: String?,
        cursor: String?,
    ): Result<ShoppedHistoryPage> =
        try {
            val databaseId = settings.shoppingDatabaseId()
                ?: error("Notion is not configured yet")
            val page = remote.fetchShoppedItems(databaseId, store, cursor)
            Result.success(
                ShoppedHistoryPage(
                    items = page.items.map { it.toDomain() },
                    nextCursor = page.nextCursor,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    override suspend fun reactivate(item: ShoppingItem, quantity: Double?, store: String?) {
        val remoteId = item.remoteId ?: return
        val now = System.currentTimeMillis()
        val cached = dao.byRemoteId(remoteId) ?: dao.byName(item.name)
        val base = cached ?: ShoppingItemEntity(
            localId = UUID.randomUUID().toString(),
            remoteId = remoteId,
            name = item.name,
            stores = item.stores,
            tags = item.tags,
            quantity = item.quantity,
            shopped = true,
            syncStatus = SyncStatus.PENDING_UPDATE,
            updatedAt = now,
        )
        dao.upsert(
            base.copy(
                shopped = false,
                tripShopped = false,
                // Reactivation is an explicit un-shop assertion, not an assumption.
                shoppedAssumed = false,
                stores = withStore(base.stores, store),
                quantity = quantity ?: base.quantity,
                syncStatus = pendingFor(base),
                updatedAt = now,
            )
        )
        // A row materialized from a remote-only item must seed its recipe refs
        // from the item's known links: a relation push sends the local ref set as
        // the page's *complete* relation, so an unseeded row would wipe every
        // link the next flush. Only when it wasn't already cached — a cached
        // row's refs came from a refresh and are authoritative, and unioning
        // would resurrect a link removed in Notion.
        if (cached == null && item.recipeIds.isNotEmpty()) {
            dao.upsertRefs(item.recipeIds.map { RecipeItemCrossRef(it, base.localId) })
        }
        syncScheduler.requestSync()
    }

    /**
     * Tags stay lowercase — deliberately unlike stores, which are title-cased.
     * Notion matches multi-select options by exact string, so a title-cased tag
     * would create a second option beside the existing lowercase one. Every tag
     * write goes through here, whichever dialog it came from.
     */
    private fun normalizeTags(tags: List<String>): List<String> =
        tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

    /** A row that hasn't been created remotely yet must stay PENDING_CREATE. */
    private fun pendingFor(item: ShoppingItemEntity): SyncStatus =
        if (item.remoteId == null) SyncStatus.PENDING_CREATE else SyncStatus.PENDING_UPDATE

    /** Append the current store view to a store list if it isn't there yet. */
    private fun withStore(stores: List<String>, store: String?): List<String> =
        if (store == null || stores.any { it.equals(store, ignoreCase = true) }) stores
        else stores + titleCase(store)
}
