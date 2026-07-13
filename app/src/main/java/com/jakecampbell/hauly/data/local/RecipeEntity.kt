package com.jakecampbell.hauly.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.jakecampbell.hauly.domain.model.SyncStatus

/**
 * Cached recipe row. Name, ingredients, instructions, and the Planned checkbox
 * are all writable and use the same offline queue semantics as items: a
 * PENDING_UPDATE row is pushed by the sync worker and never overwritten by a
 * remote refresh. (Whole-recipe create/delete are online-first, so the primary
 * key stays the Notion page id.)
 */
@Entity(tableName = "recipes")
data class RecipeEntity(
    /** Notion page id — a recipe row always has a remote page (create is online-first). */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    /** Ingredient list text (Notion `Ingredients` rich_text), newline-separated. */
    @ColumnInfo(name = "ingredients")
    val ingredients: String = "",

    /** Instruction text (Notion `Instructions` rich_text), newline-separated. */
    @ColumnInfo(name = "instructions")
    val instructions: String = "",

    /** Source link (Notion `URL` property); "" when unset. */
    @ColumnInfo(name = "url")
    val url: String = "",

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
