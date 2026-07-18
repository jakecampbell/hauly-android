package com.jakecampbell.hauly.data.repository

import com.jakecampbell.hauly.data.local.RecipeExtractionDao
import com.jakecampbell.hauly.data.local.RecipeExtractionEntity
import com.jakecampbell.hauly.data.remote.backend.ExtractRequest
import com.jakecampbell.hauly.data.remote.backend.HaulyBackendApi
import com.jakecampbell.hauly.di.ApplicationScope
import com.jakecampbell.hauly.domain.model.ExtractionStatus
import com.jakecampbell.hauly.domain.model.RecipeExtraction
import com.jakecampbell.hauly.domain.repository.RecipeExtractionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Owns extraction jobs on the hauly-backend. Room is the source of truth: the
 * UI only observes [extractions]; this class writes every status transition.
 * Polling is deliberately not WorkManager (which can't run at a 2s cadence) —
 * it's an in-process loop that lives while any extraction is unfinished and is
 * resumed on app start via [resumePolling]. The backend recovers its own
 * crashed jobs, so an app death mid-extraction only pauses observation.
 */
@Singleton
class RecipeExtractionRepositoryImpl @Inject constructor(
    private val dao: RecipeExtractionDao,
    private val api: HaulyBackendApi,
    @ApplicationScope private val appScope: CoroutineScope,
) : RecipeExtractionRepository {

    private var pollJob: Job? = null
    private val pollLock = Any()

    override fun extractions(): Flow<List<RecipeExtraction>> =
        dao.extractions().map { rows -> rows.map { it.toDomain() } }

    override fun submit(text: String, magic: Boolean) {
        // App scope, not the caller's: leaving the Recipes screen mid-POST
        // must not cancel the submission.
        appScope.launch { doSubmit(text, magic) }
    }

    /**
     * The SUBMITTING placeholder goes in before the POST so the status row
     * appears the moment the user acts — a cold-started backend can hold the
     * submit request for tens of seconds. Once the server assigns the real id
     * the placeholder is swapped for a PENDING row; a failed submit becomes a
     * FAILED row (Retry resubmits the stored text) instead of vanishing.
     */
    private suspend fun doSubmit(text: String, magic: Boolean) {
        val now = System.currentTimeMillis()
        val placeholder = RecipeExtractionEntity(
            id = LOCAL_ID_PREFIX + UUID.randomUUID(),
            sourceText = text,
            status = ExtractionStatus.SUBMITTING.name,
            title = "",
            ingredients = "",
            instructions = "",
            error = null,
            endpoint = if (magic) ENDPOINT_MAGIC else ENDPOINT_EXTRACT,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(placeholder)
        try {
            val request = ExtractRequest(content = text)
            val response =
                if (magic) api.submitMagicExtraction(request) else api.submitExtraction(request)
            // delete() returning 0 means the user cancelled the row while the
            // POST was in flight — drop the result instead of resurrecting it.
            if (dao.delete(placeholder.id) > 0) {
                dao.upsert(
                    placeholder.copy(
                        id = response.extractionId,
                        status = ExtractionStatus.PENDING.name,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                ensurePolling()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            markFailed(
                placeholder,
                if (e.code() == 401) TOKEN_REJECTED_MESSAGE else SERVICE_UNREACHABLE_MESSAGE,
            )
        } catch (e: Exception) {
            markFailed(placeholder, SERVICE_UNREACHABLE_MESSAGE)
        }
    }

    override fun retry(id: String) {
        appScope.launch {
            val row = dao.byId(id) ?: return@launch
            // The failed row is replaced by the resubmission's SUBMITTING row;
            // a failed resubmit produces its own FAILED row, so Retry survives.
            dao.delete(id)
            // Resubmit to whichever route originally built the job (R8.15).
            doSubmit(row.sourceText, magic = row.endpoint == ENDPOINT_MAGIC)
        }
    }

    override suspend fun dismiss(id: String) {
        dao.delete(id)
    }

    override fun resumePolling() {
        appScope.launch {
            // A submit POST dies with the process, so any SUBMITTING row still
            // here at app start is orphaned — its Retry resubmits the text.
            dao.submitting().forEach {
                markFailed(it, "Interrupted before it reached the recipe service.")
            }
            ensurePolling()
        }
    }

    private fun ensurePolling() {
        synchronized(pollLock) {
            if (pollJob?.isActive == true) return
            pollJob = appScope.launch { pollUntilIdle() }
        }
    }

    /**
     * Poll every active extraction until none remain. Transient failures
     * (offline, 5xx surviving the interceptor's retries) back the cadence off
     * to [MAX_POLL_DELAY_MILLIS] and keep trying while the process lives;
     * responses the backend will never resolve (401/404/422) and extractions
     * older than [EXTRACTION_TIMEOUT_MILLIS] become terminal FAILED rows.
     */
    private suspend fun pollUntilIdle() {
        var consecutiveFailures = 0
        while (true) {
            val active = dao.active()
            if (active.isEmpty()) return
            for (row in active) {
                val now = System.currentTimeMillis()
                if (now - row.createdAt > EXTRACTION_TIMEOUT_MILLIS) {
                    markFailed(row, "Timed out waiting for the recipe service.")
                    continue
                }
                try {
                    val response = api.extractionStatus(row.id)
                    consecutiveFailures = 0
                    when (response.status) {
                        "pending" -> Unit

                        "processing" -> dao.updateResult(
                            id = row.id,
                            status = ExtractionStatus.PROCESSING.name,
                            title = row.title,
                            ingredients = row.ingredients,
                            instructions = row.instructions,
                            error = null,
                            updatedAt = now,
                        )

                        "completed" -> dao.updateResult(
                            id = row.id,
                            status = ExtractionStatus.COMPLETED.name,
                            title = response.recipe?.title.orEmpty(),
                            ingredients = response.recipe?.ingredients.orEmpty(),
                            instructions = response.recipe?.instructions.orEmpty(),
                            error = null,
                            updatedAt = now,
                        )

                        "no_recipe" -> dao.updateResult(
                            id = row.id,
                            status = ExtractionStatus.NO_RECIPE.name,
                            title = row.title,
                            ingredients = row.ingredients,
                            instructions = row.instructions,
                            error = response.error ?: "Couldn't find a recipe in that text.",
                            updatedAt = now,
                        )

                        "failed" -> markFailed(row, response.error ?: "Extraction failed.")

                        // A status this client doesn't know is terminal, not
                        // "keep waiting" — otherwise the row pulses forever.
                        else -> markFailed(row, "The recipe service returned an unexpected status.")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpException) {
                    when (e.code()) {
                        401 -> markFailed(row, TOKEN_REJECTED_MESSAGE)
                        404, 422 -> markFailed(row, "The recipe service no longer knows this extraction.")
                        else -> consecutiveFailures++
                    }
                } catch (e: IOException) {
                    consecutiveFailures++
                } catch (e: Exception) {
                    // An unparseable body would otherwise kill the poll loop
                    // and strand every row mid-pulse.
                    markFailed(row, "The recipe service returned an unexpected response.")
                }
            }
            delay(
                min(
                    POLL_INTERVAL_MILLIS * (1L shl min(consecutiveFailures, 4)),
                    MAX_POLL_DELAY_MILLIS,
                )
            )
        }
    }

    /** UPDATE, not upsert: a no-op when the user already dismissed the row. */
    private suspend fun markFailed(row: RecipeExtractionEntity, message: String) {
        dao.updateResult(
            id = row.id,
            status = ExtractionStatus.FAILED.name,
            title = row.title,
            ingredients = row.ingredients,
            instructions = row.instructions,
            error = message,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun RecipeExtractionEntity.toDomain() = RecipeExtraction(
        id = id,
        status = runCatching { ExtractionStatus.valueOf(status) }
            .getOrDefault(ExtractionStatus.FAILED),
        title = title,
        ingredients = ingredients,
        instructions = instructions,
        error = error,
        createdAt = createdAt,
    )

    private companion object {
        /** Marks placeholder ids minted before the server assigns the real one. */
        const val LOCAL_ID_PREFIX = "local-"

        /** [RecipeExtractionEntity.endpoint] values — which backend route built the job. */
        const val ENDPOINT_EXTRACT = "extract"
        const val ENDPOINT_MAGIC = "magic"

        const val POLL_INTERVAL_MILLIS = 2_000L
        const val MAX_POLL_DELAY_MILLIS = 30_000L

        /** Server extractions take seconds; anything past this is stuck. */
        const val EXTRACTION_TIMEOUT_MILLIS = 5 * 60_000L

        const val TOKEN_REJECTED_MESSAGE = "Beta token rejected — check it in Settings."
        const val SERVICE_UNREACHABLE_MESSAGE = "Couldn't reach the recipe service."
    }
}
