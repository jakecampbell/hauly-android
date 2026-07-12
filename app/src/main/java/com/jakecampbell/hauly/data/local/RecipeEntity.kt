package com.jakecampbell.hauly.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.jakecampbell.hauly.domain.model.SyncStatus

/**
 * Cached recipe row. Recipe content is read-only in the app, but the Planned
 * checkbox is writable and uses the same offline queue semantics as items:
 * a PENDING_UPDATE row is pushed by the sync worker and never overwritten by
 * a remote refresh.
 */
@Entity(tableName = "recipes")
data class RecipeEntity(
    /** Notion page id — recipes only ever originate remotely. */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    /** The Notion `Planned` checkbox: the user intends to make this recipe. */
    @ColumnInfo(name = "planned")
    val planned: Boolean = false,

    /** Notion's `last_edited_time` (epoch millis), for the "recent" sort. */
    @ColumnInfo(name = "last_edited_at")
    val lastEditedAt: Long = 0L,

    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus = SyncStatus.SYNCED,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
