package com.jakecampbell.hauly.domain.repository

import com.jakecampbell.hauly.domain.model.AddItemResult
import com.jakecampbell.hauly.domain.model.EditItemResult
import com.jakecampbell.hauly.domain.model.ShoppedHistoryPage
import com.jakecampbell.hauly.domain.model.ShoppingItem
import kotlinx.coroutines.flow.Flow

interface ShoppingRepository {

    /** All active (unshopped) items from the local cache, offline-first. */
    fun activeItems(): Flow<List<ShoppingItem>>

    /**
     * Items checked off during the current shopping trip (local-only ledger),
     * in the order they were shopped. Tapping one un-shops it; [finishTrip]
     * discards the ledger.
     */
    fun tripItems(): Flow<List<ShoppingItem>>

    /** End the current trip: stop tracking the shopped items locally. */
    suspend fun finishTrip()

    /**
     * Store names known from the Notion schema plus any values seen on items,
     * ordered by most active (unshopped) items first, then by how recently an
     * item was shopped at that store.
     */
    fun storeOptions(): Flow<List<String>>

    /** Persist a drag-reorder of the visible list. Local-only; cleared per item on shop. */
    suspend fun setManualOrder(orderedLocalIds: List<String>)

    /** Tag names known from the Notion schema. */
    fun tagOptions(): Flow<List<String>>

    /** Number of local rows waiting to be pushed to Notion. */
    fun pendingCount(): Flow<Int>

    /** Pull the current remote truth into the cache (pull-to-refresh). */
    suspend fun refresh(): Result<Unit>

    /** Cached items whose name contains [query], for add-dialog type-ahead. */
    fun matchingItems(query: String): Flow<List<ShoppingItem>>

    /**
     * Add an item by name from the add dialog. A new name is created with
     * [store] as its only store tag (when a store view is active); an existing
     * row is unshopped, gets [store] appended to its stores if missing, and its
     * quantity set to [quantity] when provided.
     */
    suspend fun addItem(name: String, quantity: Double?, store: String?): AddItemResult

    suspend fun setShopped(localId: String, shopped: Boolean)

    /**
     * Edit an item's properties from the edit dialog (long-press). A null
     * [quantity] clears the Qty in Notion. Fails with [EditItemResult.DUPLICATE_NAME]
     * when [name] is already used by a different item (names are unique,
     * case-insensitive).
     */
    suspend fun updateDetails(
        localId: String,
        name: String,
        stores: List<String>,
        quantity: Double?,
    ): EditItemResult

    /**
     * Delete an item from the shopping database. Offline-safe: the row is
     * hidden immediately and queued; the sync worker archives the Notion page
     * (recoverable from the Notion trash) before removing the row for good.
     */
    suspend fun deleteItem(localId: String)

    suspend fun assignStores(localId: String, stores: List<String>)

    suspend fun assignTags(localId: String, tags: List<String>)

    /** Online-only search of the full Notion database (including shopped items). */
    suspend fun searchRemote(query: String): Result<List<ShoppingItem>>

    /**
     * Online-only, paginated browse of shopped items (most recently edited
     * first), optionally narrowed to one store. Suggestion surface only:
     * putting one back on the list goes through [reactivate].
     */
    suspend fun shoppedHistory(store: String?, cursor: String?): Result<ShoppedHistoryPage>

    /**
     * Put a remotely found (possibly shopped, not cached) item back on the
     * active list, applying the quantity/store chosen in the add dialog.
     */
    suspend fun reactivate(item: ShoppingItem, quantity: Double?, store: String?)
}
