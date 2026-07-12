package com.jakecampbell.hauly.domain.model

/**
 * Sync state of a locally cached row. Rows in a PENDING_* state are part of the
 * offline queue and must never be overwritten by a remote refresh.
 */
enum class SyncStatus {
    /** Row matches the remote Notion truth (as of the last refresh). */
    SYNCED,

    /** Row was created locally and does not exist in Notion yet. */
    PENDING_CREATE,

    /** Row has local property changes waiting to be pushed to Notion. */
    PENDING_UPDATE,

    /**
     * Row was deleted locally and is waiting for its Notion page to be
     * archived, after which the local row is removed. Hidden from every list
     * while queued.
     */
    PENDING_DELETE,

    /**
     * The last push for this row failed permanently (e.g. the page was deleted
     * in Notion). The row is rolled back to remote truth on the next refresh.
     */
    ERROR,
}
