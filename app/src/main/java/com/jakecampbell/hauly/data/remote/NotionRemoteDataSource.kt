package com.jakecampbell.hauly.data.remote

import com.jakecampbell.hauly.data.local.RecipeBlockEntity
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** Remote page snapshot of a shopping item, before local-id resolution. */
data class RemoteItem(
    val pageId: String,
    val name: String,
    val stores: List<String>,
    val tags: List<String>,
    val quantity: Double?,
    val shopped: Boolean,
    val recipeIds: List<String>,
)

data class RemoteRecipe(
    val pageId: String,
    val name: String,
    val itemPageIds: List<String>,
    val planned: Boolean,
    /** Ingredient list text (rich_text property), newlines preserved. */
    val ingredients: String,
    /** Instruction text (rich_text property), newlines preserved. */
    val instructions: String,
    /** Source link (url property); "" when unset. */
    val url: String,
    /** Notion's `last_edited_time` (epoch millis). */
    val lastEditedAt: Long,
)

/** A single page of query results with the cursor for fetching the next one. */
data class RemoteItemPage(
    val items: List<RemoteItem>,
    val nextCursor: String?,
)

/** Thin, paginated wrapper around [NotionApi] returning plain Kotlin types. */
@Singleton
class NotionRemoteDataSource @Inject constructor(
    private val api: NotionApi,
) {

    suspend fun getDatabase(databaseId: String): JsonObject = api.getDatabase(databaseId)

    /**
     * Every item the cache holds — unshopped items plus recipe-linked ones
     * (shopped or not) — following pagination to the end.
     */
    suspend fun fetchCachedItems(databaseId: String): List<RemoteItem> =
        queryAllPages(databaseId) { NotionRequests.cachedItemsQuery(it) }
            .mapNotNull(::toRemoteItem)

    /** Exact-name match (case handling is Notion's; used for duplicate prevention). */
    suspend fun findItemByName(databaseId: String, name: String): RemoteItem? {
        val envelope = api.queryDatabase(databaseId, NotionRequests.itemByNameQuery(name))
        return NotionMappers.results(envelope)
            .mapNotNull(::toRemoteItem)
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    /** Online-only search across the whole database (single page of 50 is plenty). */
    suspend fun searchItems(databaseId: String, query: String): List<RemoteItem> {
        val envelope = api.queryDatabase(databaseId, NotionRequests.searchByNameQuery(query, null))
        return NotionMappers.results(envelope).mapNotNull(::toRemoteItem)
    }

    /**
     * One page of shopped items (most recently edited first), optionally for a
     * single store. Deliberately not exhaustive: the caller pages on demand via
     * the returned cursor because the shopped set grows without bound.
     */
    suspend fun fetchShoppedItems(
        databaseId: String,
        store: String?,
        startCursor: String?,
    ): RemoteItemPage {
        val envelope =
            api.queryDatabase(databaseId, NotionRequests.shoppedHistoryQuery(store, startCursor))
        return RemoteItemPage(
            items = NotionMappers.results(envelope).mapNotNull(::toRemoteItem),
            nextCursor = NotionMappers.nextCursor(envelope),
        )
    }

    suspend fun fetchRecipes(databaseId: String): List<RemoteRecipe> =
        queryAllPages(databaseId) { NotionRequests.listQuery(it) }
            .mapNotNull(::toRemoteRecipe)

    /**
     * All instruction blocks of a recipe page, following block pagination so
     * long recipes are never cut off.
     */
    suspend fun fetchRecipeBlocks(recipePageId: String): List<RecipeBlockEntity> {
        val blocks = mutableListOf<RecipeBlockEntity>()
        var cursor: String? = null
        var index = 0
        do {
            val envelope = api.getBlockChildren(recipePageId, startCursor = cursor)
            for (raw in NotionMappers.results(envelope)) {
                NotionMappers.blockToEntity(raw, recipePageId, index)?.let {
                    blocks += it
                    index++
                }
            }
            cursor = NotionMappers.nextCursor(envelope)
        } while (cursor != null)
        return blocks
    }

    /** A single recipe page, for refreshing its content and ingredient relations. */
    suspend fun getRecipePage(pageId: String): RemoteRecipe? =
        toRemoteRecipe(api.getPage(pageId))

    /** Create a recipe page in the recipe database and return its remote snapshot. */
    suspend fun createRecipe(
        databaseId: String,
        name: String,
        ingredients: String,
        instructions: String,
        url: String,
    ): RemoteRecipe? {
        val body = NotionRequests.createPageBody(
            databaseId,
            NotionRequests.recipeProperties(name, ingredients, instructions, url, planned = false),
        )
        return toRemoteRecipe(api.createPage(body))
    }

    /** Patch a recipe's editable properties (name, ingredients, instructions, url, planned). */
    suspend fun updateRecipe(
        pageId: String,
        name: String,
        ingredients: String,
        instructions: String,
        url: String,
        planned: Boolean,
    ) {
        api.updatePage(
            pageId,
            NotionRequests.recipeUpdateBody(
                NotionRequests.recipeProperties(name, ingredients, instructions, url, planned),
            ),
        )
    }

    /** Archive (soft-delete) a recipe page; it lands in the Notion trash. */
    suspend fun archiveRecipe(pageId: String) {
        api.updatePage(pageId, NotionRequests.archivePageBody())
    }

    /** Archive (soft-delete) an item's page; it lands in the Notion trash. */
    suspend fun archiveItem(pageId: String) {
        api.updatePage(pageId, NotionRequests.archivePageBody())
    }

    suspend fun createItem(
        databaseId: String,
        name: String,
        stores: List<String>,
        tags: List<String>,
        quantity: Double?,
        shopped: Boolean,
        recipeIds: List<String>,
    ): RemoteItem? {
        val body = NotionRequests.createPageBody(
            databaseId,
            NotionRequests.itemProperties(name, stores, tags, quantity, shopped, recipeIds),
        )
        return toRemoteItem(api.createPage(body))
    }

    suspend fun updateItem(
        pageId: String,
        name: String,
        stores: List<String>,
        tags: List<String>,
        quantity: Double?,
        shopped: Boolean,
        recipeIds: List<String>,
    ): RemoteItem? {
        val body = NotionRequests.updatePageBody(
            NotionRequests.itemProperties(name, stores, tags, quantity, shopped, recipeIds),
        )
        return toRemoteItem(api.updatePage(pageId, body))
    }

    private suspend fun queryAllPages(
        databaseId: String,
        body: (cursor: String?) -> JsonObject,
    ): List<JsonObject> {
        val pages = mutableListOf<JsonObject>()
        var cursor: String? = null
        do {
            val envelope = api.queryDatabase(databaseId, body(cursor))
            pages += NotionMappers.results(envelope)
            cursor = NotionMappers.nextCursor(envelope)
        } while (cursor != null)
        return pages
    }

    private fun toRemoteRecipe(page: JsonObject): RemoteRecipe? {
        val id = NotionMappers.pageId(page) ?: return null
        val props = NotionMappers.properties(page) ?: return null
        return RemoteRecipe(
            pageId = id,
            name = NotionMappers.titleText(props, NotionSchema.PROP_NAME),
            itemPageIds = NotionMappers.relationIds(props, NotionSchema.PROP_SHOPPING),
            planned = NotionMappers.checkboxValue(props, NotionSchema.PROP_PLANNED),
            ingredients = NotionMappers.richTextValue(props, NotionSchema.PROP_INGREDIENTS),
            instructions = NotionMappers.richTextValue(props, NotionSchema.PROP_INSTRUCTIONS),
            url = NotionMappers.urlValue(props, NotionSchema.PROP_URL),
            lastEditedAt = NotionMappers.lastEditedTime(page) ?: 0L,
        )
    }

    private fun toRemoteItem(page: JsonObject): RemoteItem? {
        val id = NotionMappers.pageId(page) ?: return null
        val props = NotionMappers.properties(page) ?: return null
        val name = NotionMappers.titleText(props, NotionSchema.PROP_NAME)
        if (name.isBlank()) return null
        return RemoteItem(
            pageId = id,
            name = name,
            stores = NotionMappers.multiSelectValues(props, NotionSchema.PROP_STORE),
            tags = NotionMappers.multiSelectValues(props, NotionSchema.PROP_TAG),
            quantity = NotionMappers.numberValue(props, NotionSchema.PROP_QUANTITY),
            shopped = NotionMappers.checkboxValue(props, NotionSchema.PROP_SHOPPED),
            recipeIds = NotionMappers.relationIds(props, NotionSchema.PROP_RECIPES),
        )
    }
}
