package com.jakecampbell.hauly.data.repository

import com.jakecampbell.hauly.data.local.RecipeDao
import com.jakecampbell.hauly.data.local.RecipeItemCrossRef
import com.jakecampbell.hauly.data.local.ShoppingItemDao
import com.jakecampbell.hauly.data.local.ShoppingItemEntity
import com.jakecampbell.hauly.data.remote.NotionRemoteDataSource
import com.jakecampbell.hauly.data.sync.SyncEngine
import com.jakecampbell.hauly.data.sync.SyncScheduler
import com.jakecampbell.hauly.domain.model.AddItemResult
import com.jakecampbell.hauly.domain.model.Recipe
import com.jakecampbell.hauly.domain.model.RecipeBlock
import com.jakecampbell.hauly.domain.model.ShoppingItem
import com.jakecampbell.hauly.domain.model.SyncStatus
import com.jakecampbell.hauly.domain.repository.RecipeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecipeRepositoryImpl @Inject constructor(
    private val recipeDao: RecipeDao,
    private val itemDao: ShoppingItemDao,
    private val remote: NotionRemoteDataSource,
    private val syncEngine: SyncEngine,
    private val syncScheduler: SyncScheduler,
) : RecipeRepository {

    override fun recipes(): Flow<List<Recipe>> =
        recipeDao.recipes().map { list -> list.map { it.toDomain() } }

    override fun recipe(id: String): Flow<Recipe?> =
        recipeDao.recipe(id).map { it?.toDomain() }

    override fun blocks(recipeId: String): Flow<List<RecipeBlock>> =
        recipeDao.blocks(recipeId).map { entities ->
            // Assign 1-based ordinals to runs of consecutive numbered list items.
            var ordinal = 0
            entities.map { entity ->
                ordinal = if (entity.type == "numbered_list_item") ordinal + 1 else 0
                entity.toDomain(ordinal)
            }
        }

    override fun ingredients(recipeId: String): Flow<List<ShoppingItem>> =
        itemDao.itemsForRecipe(recipeId).map { list -> list.map { it.toDomain() } }

    override suspend fun refreshRecipes(): Result<Unit> = syncEngine.refreshRecipes()

    override suspend fun refreshRecipeDetail(recipeId: String): Result<Unit> =
        try {
            // Page blocks, following pagination so long recipes are complete.
            recipeDao.replaceBlocks(recipeId, remote.fetchRecipeBlocks(recipeId))

            // Refresh this recipe's ingredient relations from the recipe page.
            val page = remote.getRecipePage(recipeId)
            if (page != null) {
                val refs = page.itemPageIds.mapNotNull { pageId ->
                    itemDao.byRemoteId(pageId)
                        ?.let { RecipeItemCrossRef(recipeId, it.localId) }
                }
                if (refs.isNotEmpty()) itemDao.upsertRefs(refs)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    override suspend fun setPlanned(recipeId: String, planned: Boolean) {
        val recipe = recipeDao.byId(recipeId) ?: return
        val now = System.currentTimeMillis()
        recipeDao.upsert(
            recipe.copy(
                planned = planned,
                // Local approximation of Notion's last_edited_time so the
                // "recent" sort reflects the toggle before the next refresh.
                lastEditedAt = now,
                syncStatus = SyncStatus.PENDING_UPDATE,
                updatedAt = now,
            )
        )
        syncScheduler.requestSync()
    }

    override suspend fun addIngredient(
        recipeId: String,
        name: String,
        quantity: Double?,
    ): AddItemResult {
        val now = System.currentTimeMillis()
        val existing = itemDao.byName(name)

        if (existing == null) {
            // Not cached: create locally with the Grocery store and link the
            // recipe relation. If the name already exists in Notion (a shopped,
            // evicted item), the create-flush merges into it instead of duplicating.
            val localId = UUID.randomUUID().toString()
            itemDao.upsert(
                ShoppingItemEntity(
                    localId = localId,
                    remoteId = null,
                    name = name,
                    stores = listOf(RecipeRepository.DEFAULT_INGREDIENT_STORE),
                    tags = emptyList(),
                    quantity = quantity,
                    shopped = false,
                    syncStatus = SyncStatus.PENDING_CREATE,
                    updatedAt = now,
                )
            )
            itemDao.upsertRefs(listOf(RecipeItemCrossRef(recipeId, localId)))
            syncScheduler.requestSync()
            return AddItemResult.CREATED
        }

        val alreadyLinked = itemDao.ref(recipeId, existing.localId) != null
        itemDao.upsertRefs(listOf(RecipeItemCrossRef(recipeId, existing.localId)))

        val grocery = RecipeRepository.DEFAULT_INGREDIENT_STORE
        val updated = existing.copy(
            shopped = false,
            tripShopped = false,
            stores = if (existing.stores.any { it.equals(grocery, ignoreCase = true) }) {
                existing.stores
            } else {
                existing.stores + grocery
            },
            quantity = quantity ?: existing.quantity,
        )
        if (updated != existing || !alreadyLinked) {
            val status = if (existing.remoteId == null) SyncStatus.PENDING_CREATE
            else SyncStatus.PENDING_UPDATE
            itemDao.upsert(updated.copy(syncStatus = status, updatedAt = now))
            syncScheduler.requestSync()
        }
        return when {
            existing.shopped -> AddItemResult.REACTIVATED
            !alreadyLinked -> AddItemResult.CREATED
            else -> AddItemResult.ALREADY_ACTIVE
        }
    }
}
