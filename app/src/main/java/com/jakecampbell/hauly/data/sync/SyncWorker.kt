package com.jakecampbell.hauly.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jakecampbell.hauly.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Flushes the offline edit queue to Notion, then re-pulls remote truth so any
 * rows that failed permanently are rolled back to the remote state.
 * Scheduled with a network constraint, so it naturally runs when the
 * connection is restored after offline edits.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncEngine: SyncEngine,
    private val settings: SettingsRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!settings.isConfigured.first()) return Result.success()

        if (syncEngine.flushQueue() == SyncEngine.FlushOutcome.RETRY) {
            return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }

        // Refresh failures here are non-fatal: the queue is already flushed and
        // the next refresh (manual or scheduled) will converge the cache.
        syncEngine.refreshShopping()
        syncEngine.refreshRecipes()
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "hauly-notion-sync"
        private const val MAX_RETRIES = 8
    }
}
