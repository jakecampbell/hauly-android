package com.jakecampbell.hauly.data.remote

import com.jakecampbell.hauly.data.local.RecipeBlockEntity
import com.jakecampbell.hauly.domain.model.SchemaProblem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extraction helpers for Notion's polymorphic JSON. Everything is null-safe:
 * a malformed page degrades to defaults instead of crashing the sync.
 */
object NotionMappers {

    fun pageId(page: JsonObject): String? =
        (page["id"] as? JsonPrimitive)?.contentOrNull

    /** The page's `last_edited_time` as epoch millis, or null when absent/malformed. */
    fun lastEditedTime(page: JsonObject): Long? =
        (page["last_edited_time"] as? JsonPrimitive)?.contentOrNull?.let { iso ->
            runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull()
        }

    fun properties(page: JsonObject): JsonObject? =
        page["properties"] as? JsonObject

    fun titleText(properties: JsonObject, name: String): String =
        richTextToPlain((properties[name] as? JsonObject)?.get("title") as? JsonArray)

    /**
     * A rich_text *property*'s plain text (distinct from a title). Internal
     * newlines are preserved; leading/trailing whitespace is trimmed.
     */
    fun richTextValue(properties: JsonObject, name: String): String =
        richTextToPlain((properties[name] as? JsonObject)?.get("rich_text") as? JsonArray)

    fun multiSelectValues(properties: JsonObject, name: String): List<String> =
        ((properties[name] as? JsonObject)?.get("multi_select") as? JsonArray)
            ?.mapNotNull { (it.jsonObject["name"] as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()

    fun numberValue(properties: JsonObject, name: String): Double? =
        ((properties[name] as? JsonObject)?.get("number") as? JsonPrimitive)?.doubleOrNull

    /** A url-type property's value, or "" when empty/absent. */
    fun urlValue(properties: JsonObject, name: String): String =
        ((properties[name] as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull ?: ""

    fun checkboxValue(properties: JsonObject, name: String): Boolean =
        ((properties[name] as? JsonObject)?.get("checkbox") as? JsonPrimitive)
            ?.booleanOrNull ?: false

    fun relationIds(properties: JsonObject, name: String): List<String> =
        ((properties[name] as? JsonObject)?.get("relation") as? JsonArray)
            ?.mapNotNull { (it.jsonObject["id"] as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()

    // --- Pagination envelope ---

    fun results(envelope: JsonObject): List<JsonObject> =
        (envelope["results"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    fun nextCursor(envelope: JsonObject): String? {
        if ((envelope["has_more"] as? JsonPrimitive)?.booleanOrNull != true) return null
        return (envelope["next_cursor"] as? JsonPrimitive)?.contentOrNull
    }

    // --- Schema validation ---

    /**
     * Compare a `GET /databases/{id}` response against required properties.
     * Returns one [SchemaProblem] per missing or wrongly-typed property.
     */
    fun validateSchema(
        database: JsonObject,
        databaseLabel: String,
        required: Map<String, String>,
    ): List<SchemaProblem> {
        val actual = database["properties"] as? JsonObject ?: JsonObject(emptyMap())
        return required.mapNotNull { (property, expectedType) ->
            val actualType = (actual[property] as? JsonObject)
                ?.get("type")?.jsonPrimitive?.contentOrNull
            when (actualType) {
                expectedType -> null
                else -> SchemaProblem(databaseLabel, property, expectedType, actualType)
            }
        }
    }

    /** The database a relation property points at, from a database schema response. */
    fun relationTargetDatabase(database: JsonObject, property: String): String? =
        ((database["properties"] as? JsonObject)
            ?.get(property)?.jsonObject
            ?.get("relation") as? JsonObject)
            ?.get("database_id")?.jsonPrimitive?.contentOrNull

    /** Multi-select options declared in the database schema for a property. */
    fun schemaSelectOptions(database: JsonObject, property: String): Set<String> =
        ((database["properties"] as? JsonObject)
            ?.get(property)?.jsonObject
            ?.get("multi_select")?.jsonObject
            ?.get("options") as? JsonArray)
            ?.mapNotNull { (it.jsonObject["name"] as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            ?: emptySet()

    // --- Blocks ---

    private val textBearingBlockTypes = setOf(
        "paragraph", "heading_1", "heading_2", "heading_3",
        "bulleted_list_item", "numbered_list_item", "to_do", "quote", "callout",
    )

    /** Map a raw block to a cacheable entity; returns null for skippable blocks. */
    fun blockToEntity(block: JsonObject, recipeId: String, orderIndex: Int): RecipeBlockEntity? {
        val id = pageId(block) ?: return null
        val type = (block["type"] as? JsonPrimitive)?.contentOrNull ?: return null
        val payload = block[type] as? JsonObject

        if (type == "divider") {
            return RecipeBlockEntity(id, recipeId, orderIndex, type, "")
        }

        val text = richTextToPlain(payload?.get("rich_text") as? JsonArray)
        if (type !in textBearingBlockTypes) {
            // Unsupported block (image, table, ...): keep it only if it carries text.
            return if (text.isBlank()) null
            else RecipeBlockEntity(id, recipeId, orderIndex, "paragraph", text)
        }
        if (text.isBlank()) return null

        val checked = (payload?.get("checked") as? JsonPrimitive)?.booleanOrNull ?: false
        return RecipeBlockEntity(id, recipeId, orderIndex, type, text, checked)
    }

    private fun richTextToPlain(richText: JsonArray?): String =
        richText?.joinToString("") {
            (it.jsonObject["plain_text"] as? JsonPrimitive)?.contentOrNull ?: ""
        }?.trim() ?: ""
}
