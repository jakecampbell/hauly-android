package com.jakecampbell.hauly.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jakecampbell.hauly.domain.model.SyncStatus

/**
 * Cached shopping list row. The cache policy is active-items-heavy: shopped
 * items are evicted on refresh and only reachable through online search.
 *
 * [localId] is a stable app-generated UUID so locally created rows keep their
 * identity once Notion assigns them a page id ([remoteId]).
 */
@Entity(
    tableName = "shopping_items",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["remote_id"], unique = true),
        Index(value = ["sync_status"]),
    ],
)
data class ShoppingItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "local_id")
    val localId: String,

    @ColumnInfo(name = "remote_id")
    val remoteId: String?,

    /** NOCASE collation makes the unique index case-insensitive ("Milk" == "milk"). */
    @ColumnInfo(name = "name", collate = ColumnInfo.NOCASE)
    val name: String,

    @ColumnInfo(name = "stores")
    val stores: List<String>,

    @ColumnInfo(name = "tags")
    val tags: List<String>,

    @ColumnInfo(name = "quantity")
    val quantity: Double?,

    @ColumnInfo(name = "shopped")
    val shopped: Boolean,

    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    /**
     * Local-only manual sort position from drag-reordering. Never synced to
     * Notion and cleared when the item is shopped. Null = not manually placed.
     */
    @ColumnInfo(name = "manual_rank")
    val manualRank: Int? = null,

    /**
     * Local-only flag: checked off during the current shopping trip. Rows with
     * this flag survive cache eviction so they can be un-shopped with one tap;
     * the "Done" button ends the trip and discards them.
     */
    @ColumnInfo(name = "trip_shopped")
    val tripShopped: Boolean = false,
)
