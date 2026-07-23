package com.jakecampbell.hauly.data.repository

import com.jakecampbell.hauly.data.local.RecipeDao
import com.jakecampbell.hauly.data.local.RecipeEntity
import com.jakecampbell.hauly.data.local.RecipeItemCrossRef
import com.jakecampbell.hauly.data.local.ShoppingItemDao
import com.jakecampbell.hauly.data.local.ShoppingItemEntity
import com.jakecampbell.hauly.data.remote.NotionRemoteDataSource
import com.jakecampbell.hauly.data.settings.SettingsRepository
import com.jakecampbell.hauly.data.sync.SyncEngine
import com.jakecampbell.hauly.data.sync.SyncScheduler
import com.jakecampbell.hauly.domain.model.AddItemResult
import com.jakecampbell.hauly.domain.model.Recipe
import com.jakecampbell.hauly.domain.model.RecipeBlock
import com.jakecampbell.hauly.domain.model.RecipeSection
import com.jakecampbell.hauly.domain.model.RecipeSort
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
    private val settings: SettingsRepository,
    private val syncEngine: SyncEngine,
    private val syncScheduler: SyncScheduler,
) : RecipeRepository {

    override fun recipes(): Flow<List<Recipe>> =
        recipeDao.recipes().map { list -> list.map { it.toDomain() } }

    override fun recipeSort(): Flow<RecipeSort> = settings.recipeSort

    override suspend fun setRecipeSort(mode: RecipeSort) = settings.setRecipeSort(mode)

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

    override fun struckLines(recipeId: String): Flow<Map<RecipeSection, Set<Int>>> =
        recipeDao.lineMarks(recipeId).map { marks ->
            marks.groupBy { RecipeSection.valueOf(it.section) }
                .mapValues { (_, rows) -> rows.map { it.lineIndex }.toSet() }
        }

    override suspend fun toggleLineMark(recipeId: String, section: RecipeSection, lineIndex: Int) {
        recipeDao.toggleMark(recipeId, section.name, lineIndex)
    }

    override suspend fun clearLineMarks(recipeId: String) {
        recipeDao.clearAllMarks(recipeId)
    }

    override suspend fun saveIngredients(recipeId: String, text: String) {
        updateContent(recipeId, clearSection = RecipeSection.INGREDIENTS) { it.copy(ingredients = text) }
    }

    override suspend fun saveInstructions(recipeId: String, text: String) {
        updateContent(recipeId, clearSection = RecipeSection.INSTRUCTIONS) { it.copy(instructions = text) }
    }

    override suspend fun saveUrl(recipeId: String, url: String) {
        updateContent(recipeId, clearSection = null) { it.copy(url = url.trim()) }
    }

    override suspend fun renameRecipe(recipeId: String, name: String) {
        updateContent(recipeId, clearSection = null) { it.copy(name = name) }
    }

    /**
     * Apply an editable-field change and queue it. Bumps `last_edited_at` so the
     * "recent" sort reflects the edit before the next refresh, and clears the
     * edited section's line strikes (line positions shift on edit).
     */
    private suspend fun updateContent(
        recipeId: String,
        clearSection: RecipeSection?,
        edit: (RecipeEntity) -> RecipeEntity,
    ) {
        val recipe = recipeDao.byId(recipeId) ?: return
        val now = System.currentTimeMillis()
        recipeDao.upsert(
            edit(recipe).copy(
                lastEditedAt = now,
                syncStatus = SyncStatus.PENDING_UPDATE,
                updatedAt = now,
            )
        )
        if (clearSection != null) recipeDao.clearMarks(recipeId, clearSection.name)
        syncScheduler.requestSync()
    }

    override suspend fun createRecipe(
        name: String,
        ingredients: String,
        instructions: String,
        url: String,
    ): Result<String> = try {
        val databaseId = settings.recipeDatabaseId()
        val created = if (databaseId == null) null
        else remote.createRecipe(databaseId, name, ingredients, instructions, url)
        if (created == null) {
            Result.failure(IllegalStateException("Couldn't create the recipe in Notion."))
        } else {
            val now = System.currentTimeMillis()
            recipeDao.upsert(
                RecipeEntity(
                    id = created.pageId,
                    name = created.name,
                    ingredients = created.ingredients,
                    instructions = created.instructions,
                    url = created.url,
                    planned = created.planned,
                    lastEditedAt = created.lastEditedAt.takeIf { it > 0L } ?: now,
                    syncStatus = SyncStatus.SYNCED,
                    updatedAt = now,
                )
            )
            Result.success(created.pageId)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun deleteRecipe(recipeId: String): Result<Unit> = try {
        remote.archiveRecipe(recipeId)
        recipeDao.deleteRecipe(recipeId)
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun refreshRecipes(): Result<Unit> = syncEngine.refreshRecipes()

    override suspend fun refreshRecipeDetail(recipeId: String): Result<Unit> =
        try {
            // Page blocks, following pagination so long recipes are complete.
            recipeDao.replaceBlocks(recipeId, remote.fetchRecipeBlocks(recipeId))

            // Refresh this recipe's content and ingredient relations from the page.
            val page = remote.getRecipePage(recipeId)
            if (page != null) {
                // Pull remote content into the cache, but never clobber a queued
                // or in-flight local edit (compare-and-set on updated_at).
                val local = recipeDao.byId(recipeId)
                if (local != null && local.syncStatus == SyncStatus.SYNCED) {
                    recipeDao.upsertIfUnchanged(
                        local.copy(
                            name = page.name,
                            ingredients = page.ingredients,
                            instructions = page.instructions,
                            url = page.url,
                            planned = page.planned,
                            lastEditedAt = page.lastEditedAt,
                            updatedAt = System.currentTimeMillis(),
                        ),
                        local.updatedAt,
                    )
                }
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
        suggestion: ShoppingItem?,
    ): AddItemResult {
        val now = System.currentTimeMillis()
        val existing = itemDao.byName(name)

        if (existing == null) {
            // Not cached: create locally with the Grocery store and link the
            // recipe relation. Here [suggestion] is always a remote-only match (a
            // cached one would have been found by byName). If the name already
            // exists in Notion (a shopped, evicted item), the create-flush merges
            // into it instead of duplicating — so we seed the row with the
            // suggestion's shopped state to avoid un-shopping that page on merge,
            // and seed the refs from the suggestion's known recipe links (plus
            // this recipe) so a later relation push doesn't wipe them. A relation
            // push sends the local ref set as the page's *complete* relation.
            //
            // With no suggestion (name typed in full, or added offline) there is
            // no shopped state to seed from: create the row unshopped but mark it
            // `shoppedAssumed`, so the create-with-merge flush adopts the existing
            // page's Shopped instead of un-shopping it (R5.4/R8.5).
            val shopped = suggestion?.shopped == true
            val localId = UUID.randomUUID().toString()
            itemDao.upsert(
                ShoppingItemEntity(
                    localId = localId,
                    remoteId = null,
                    name = name,
                    stores = listOf(RecipeRepository.DEFAULT_INGREDIENT_STORE),
                    tags = emptyList(),
                    quantity = quantity,
                    shopped = shopped,
                    shoppedAssumed = suggestion == null,
                    syncStatus = SyncStatus.PENDING_CREATE,
                    updatedAt = now,
                )
            )
            val refRecipeIds = (suggestion?.recipeIds.orEmpty() + recipeId).distinct()
            itemDao.upsertRefs(refRecipeIds.map { RecipeItemCrossRef(it, localId) })
            syncScheduler.requestSync()
            return if (shopped) AddItemResult.ADDED_SHOPPED else AddItemResult.CREATED
        }

        val alreadyLinked = itemDao.ref(recipeId, existing.localId) != null
        itemDao.upsertRefs(listOf(RecipeItemCrossRef(recipeId, existing.localId)))

        // Preserve the item's current shopped state: an ingredient added from a
        // recipe keeps whatever shopped status it has, so a shopped item stays
        // crossed out in the recipe's list rather than being pulled back onto
        // the active list. (The shopping-list add path still reactivates.)
        val grocery = RecipeRepository.DEFAULT_INGREDIENT_STORE
        val updated = existing.copy(
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
            !alreadyLinked && existing.shopped -> AddItemResult.ADDED_SHOPPED
            !alreadyLinked -> AddItemResult.CREATED
            else -> AddItemResult.ALREADY_ACTIVE
        }
    }

    override suspend fun removeIngredient(recipeId: String, itemLocalId: String) {
        val item = itemDao.byLocalId(itemLocalId) ?: return
        if (itemDao.ref(recipeId, itemLocalId) == null) return

        itemDao.deleteRef(recipeId, itemLocalId)
        // The item's own properties are untouched; queueing it is what gets the
        // shortened ref set pushed, since the flush sends the local refs as the
        // page's complete relation. A row with no page id yet stays
        // PENDING_CREATE so its create carries the reduced set.
        val status = if (item.remoteId == null) SyncStatus.PENDING_CREATE
        else SyncStatus.PENDING_UPDATE
        itemDao.upsert(item.copy(syncStatus = status, updatedAt = System.currentTimeMillis()))
        syncScheduler.requestSync()
    }
}
