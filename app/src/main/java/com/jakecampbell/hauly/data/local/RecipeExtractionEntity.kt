package com.jakecampbell.hauly.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.jakecampbell.hauly.domain.model.SyncStatus

/**
 * An in-flight (or finished-but-unreviewed) recipe extraction on the
 * hauly-backend. Rows are created when clipboard text is submitted, updated by
 * the poll loop, and deleted when the user creates the recipe or dismisses the
 * row. Device-local: this table is never pushed to Notion.
 */
@Entity(tableName = "recipe_extractions")
data class RecipeExtractionEntity(
    /** Server-issued extraction id (UUID). A retry creates a new row. */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** The full submitted text, kept so a failed extraction can be resubmitted. */
    @ColumnInfo(name = "source_text")
    val sourceText: String,

    /** [com.jakecampbell.hauly.domain.model.ExtractionStatus] name. */
    @ColumnInfo(name = "status")
    val status: String,

    /** Extracted recipe name; "" until completed. */
    @ColumnInfo(name = "title")
    val title: String,

    /** Extracted ingredients, newline-separated; "" until completed. */
    @ColumnInfo(name = "ingredients")
    val ingredients: String,

    /** Extracted instructions, newline-separated; "" until completed. */
    @ColumnInfo(name = "instructions")
    val instructions: String,

    /** Human-readable failure reason; null unless FAILED. */
    @ColumnInfo(name = "error")
    val error: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    /**
     * Always SYNCED: the sync engine ignores this table (like recipe_line_marks
     * it is client-server job state, not Notion data). The column exists for
     * entity uniformity only.
     */
    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
)
