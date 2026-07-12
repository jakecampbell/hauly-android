package com.jakecampbell.hauly.data.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Builders for Notion request bodies. */
object NotionRequests {

    /**
     * Query for every item the cache holds: active (unshopped) items plus any
     * item linked to a recipe — recipe ingredient lists must always show their
     * items, shopped or not.
     */
    fun cachedItemsQuery(startCursor: String?): JsonObject = buildJsonObject {
        putJsonObject("filter") {
            putJsonArray("or") {
                addJsonObject {
                    put("property", NotionSchema.PROP_SHOPPED)
                    putJsonObject("checkbox") { put("equals", false) }
                }
                addJsonObject {
                    put("property", NotionSchema.PROP_RECIPES)
                    putJsonObject("relation") { put("is_not_empty", true) }
                }
            }
        }
        put("page_size", 100)
        if (startCursor != null) put("start_cursor", startCursor)
    }

    /** Exact-name lookup used for duplicate prevention during create-sync. */
    fun itemByNameQuery(name: String): JsonObject = buildJsonObject {
        putJsonObject("filter") {
            put("property", NotionSchema.PROP_NAME)
            putJsonObject("title") { put("equals", name) }
        }
        put("page_size", 5)
    }

    /** Online search across the whole database, shopped items included. */
    fun searchByNameQuery(query: String, startCursor: String?): JsonObject = buildJsonObject {
        putJsonObject("filter") {
            put("property", NotionSchema.PROP_NAME)
            putJsonObject("title") { put("contains", query) }
        }
        put("page_size", 50)
        if (startCursor != null) put("start_cursor", startCursor)
    }

    /**
     * One page of shopped items, most recently edited first, optionally
     * narrowed to a store. Backs the collapsible "shopped items" browse on the
     * shopping screen, which pages on demand because this set grows forever.
     */
    fun shoppedHistoryQuery(store: String?, startCursor: String?): JsonObject = buildJsonObject {
        val shoppedFilter = buildJsonObject {
            put("property", NotionSchema.PROP_SHOPPED)
            putJsonObject("checkbox") { put("equals", true) }
        }
        if (store == null) {
            put("filter", shoppedFilter)
        } else {
            putJsonObject("filter") {
                putJsonArray("and") {
                    add(shoppedFilter)
                    addJsonObject {
                        put("property", NotionSchema.PROP_STORE)
                        putJsonObject("multi_select") { put("contains", store) }
                    }
                }
            }
        }
        putJsonArray("sorts") {
            addJsonObject {
                put("timestamp", "last_edited_time")
                put("direction", "descending")
            }
        }
        put("page_size", 50)
        if (startCursor != null) put("start_cursor", startCursor)
    }

    /** Plain paginated query (used for the recipe list). */
    fun listQuery(startCursor: String?): JsonObject = buildJsonObject {
        put("page_size", 100)
        if (startCursor != null) put("start_cursor", startCursor)
    }

    /**
     * Full property payload for a shopping item, shared by create and update.
     * Store/Tag multi-selects are always sent; for newly created items they are
     * empty by design so the user can categorize later.
     */
    fun itemProperties(
        name: String,
        stores: List<String>,
        tags: List<String>,
        quantity: Double?,
        shopped: Boolean,
        recipeIds: List<String>,
    ): JsonObject = buildJsonObject {
        putJsonObject(NotionSchema.PROP_NAME) {
            putJsonArray("title") {
                addJsonObject { putJsonObject("text") { put("content", name) } }
            }
        }
        putJsonObject(NotionSchema.PROP_STORE) {
            putJsonArray("multi_select") {
                stores.forEach { addJsonObject { put("name", it) } }
            }
        }
        putJsonObject(NotionSchema.PROP_TAG) {
            putJsonArray("multi_select") {
                tags.forEach { addJsonObject { put("name", it) } }
            }
        }
        putJsonObject(NotionSchema.PROP_QUANTITY) {
            if (quantity != null) put("number", quantity) else put("number", null as Double?)
        }
        putJsonObject(NotionSchema.PROP_SHOPPED) { put("checkbox", shopped) }
        putJsonObject(NotionSchema.PROP_RECIPES) {
            putJsonArray("relation") {
                recipeIds.forEach { addJsonObject { put("id", it) } }
            }
        }
    }

    fun createPageBody(databaseId: String, properties: JsonObject): JsonObject =
        buildJsonObject {
            putJsonObject("parent") { put("database_id", databaseId) }
            put("properties", properties)
        }

    /**
     * `archived: false` is a no-op on live pages but restores a page that a
     * racing delete flush archived — updating an archived page without it
     * fails with a 400, which would strand a resurrected item in ERROR.
     */
    fun updatePageBody(properties: JsonObject): JsonObject =
        buildJsonObject {
            put("archived", false)
            put("properties", properties)
        }

    /** Notion has no hard delete over the API; archiving moves the page to trash. */
    fun archivePageBody(): JsonObject = buildJsonObject { put("archived", true) }

    /** Minimal recipe patch: flips only the Planned checkbox. */
    fun setPlannedBody(planned: Boolean): JsonObject = buildJsonObject {
        putJsonObject("properties") {
            putJsonObject(NotionSchema.PROP_PLANNED) { put("checkbox", planned) }
        }
    }
}
