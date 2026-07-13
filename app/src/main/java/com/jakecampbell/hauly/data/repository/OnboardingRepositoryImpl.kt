package com.jakecampbell.hauly.data.repository

import com.jakecampbell.hauly.data.remote.NotionApi
import com.jakecampbell.hauly.data.remote.NotionMappers
import com.jakecampbell.hauly.data.remote.NotionSchema
import com.jakecampbell.hauly.data.settings.SettingsRepository
import com.jakecampbell.hauly.data.sync.SyncScheduler
import com.jakecampbell.hauly.domain.model.SchemaProblem
import com.jakecampbell.hauly.domain.model.SetupValidation
import com.jakecampbell.hauly.domain.repository.OnboardingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnboardingRepositoryImpl @Inject constructor(
    private val api: NotionApi,
    private val settings: SettingsRepository,
    private val syncScheduler: SyncScheduler,
) : OnboardingRepository {

    override val isConfigured: Flow<Boolean> = settings.isConfigured

    override suspend fun validateAndSave(
        token: String,
        shoppingDatabaseId: String,
        recipeDatabaseId: String,
    ): SetupValidation {
        val bearer = "Bearer ${token.trim()}"

        val shoppingDb = fetchDatabase(shoppingDatabaseId.trim(), bearer, NotionSchema.SHOPPING_DB_LABEL)
            .getOrElse { return SetupValidation.Failed(it.message ?: "Validation failed") }
        val recipeDb = fetchDatabase(recipeDatabaseId.trim(), bearer, NotionSchema.RECIPE_DB_LABEL)
            .getOrElse { return SetupValidation.Failed(it.message ?: "Validation failed") }

        val problems =
            NotionMappers.validateSchema(
                shoppingDb, NotionSchema.SHOPPING_DB_LABEL, NotionSchema.shoppingListProperties
            ) + NotionMappers.validateSchema(
                recipeDb, NotionSchema.RECIPE_DB_LABEL, NotionSchema.recipeProperties
            ) + crossRelationProblems(shoppingDb, recipeDb, shoppingDatabaseId.trim(), recipeDatabaseId.trim())

        if (problems.isNotEmpty()) return SetupValidation.Invalid(problems)

        settings.saveConfiguration(token.trim(), shoppingDatabaseId.trim(), recipeDatabaseId.trim())
        settings.saveSelectOptions(
            stores = NotionMappers.schemaSelectOptions(shoppingDb, NotionSchema.PROP_STORE),
            tags = NotionMappers.schemaSelectOptions(shoppingDb, NotionSchema.PROP_TAG),
        )
        syncScheduler.requestSync()
        return SetupValidation.Valid
    }

    private suspend fun fetchDatabase(
        databaseId: String,
        bearer: String,
        label: String,
    ): Result<JsonObject> =
        try {
            Result.success(api.getDatabaseWithToken(databaseId, bearer))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val message = when (e.code()) {
                401 -> "Notion rejected the token. Double-check your integration secret."
                403 -> "The token has no access to the $label database. Share the database with your integration in Notion."
                404 -> "$label database not found. Check the database ID and that it is shared with your integration."
                else -> "Notion returned HTTP ${e.code()} while checking the $label database."
            }
            Result.failure(IOException(message))
        } catch (e: IOException) {
            Result.failure(IOException("Could not reach Notion. Check your internet connection."))
        }

    /**
     * Verify the two relation properties actually point at each other, so a
     * user pasting the wrong database ID gets a precise error.
     */
    private fun crossRelationProblems(
        shoppingDb: JsonObject,
        recipeDb: JsonObject,
        shoppingDatabaseId: String,
        recipeDatabaseId: String,
    ): List<SchemaProblem> {
        val problems = mutableListOf<SchemaProblem>()
        val recipesTarget = NotionMappers.relationTargetDatabase(shoppingDb, NotionSchema.PROP_RECIPES)
        if (recipesTarget != null && !sameNotionId(recipesTarget, recipeDatabaseId)) {
            problems += SchemaProblem(
                database = NotionSchema.SHOPPING_DB_LABEL,
                property = NotionSchema.PROP_RECIPES,
                expectedType = "relation to the Recipe database",
                actualType = "relation to a different database",
            )
        }
        val itemsTarget = NotionMappers.relationTargetDatabase(recipeDb, NotionSchema.PROP_SHOPPING)
        if (itemsTarget != null && !sameNotionId(itemsTarget, shoppingDatabaseId)) {
            problems += SchemaProblem(
                database = NotionSchema.RECIPE_DB_LABEL,
                property = NotionSchema.PROP_SHOPPING,
                expectedType = "relation to the Shopping List database",
                actualType = "relation to a different database",
            )
        }
        return problems
    }

    /** Notion ids compare equal with or without dashes. */
    private fun sameNotionId(a: String, b: String): Boolean =
        a.replace("-", "").equals(b.replace("-", ""), ignoreCase = true)
}
