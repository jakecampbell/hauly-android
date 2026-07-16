package com.jakecampbell.hauly.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.jakecampbell.hauly.domain.model.RecipeSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted app configuration: the Notion PAT, database ids and the
 * multi-select options (stores/tags) read from the Shopping List schema.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val TOKEN = stringPreferencesKey("notion_token")
        val SHOPPING_DB = stringPreferencesKey("shopping_db_id")
        val RECIPE_DB = stringPreferencesKey("recipe_db_id")
        val CONFIGURED = booleanPreferencesKey("configured")
        val STORE_OPTIONS = stringSetPreferencesKey("store_options")
        val TAG_OPTIONS = stringSetPreferencesKey("tag_options")
        val LAST_SYNC = longPreferencesKey("last_sync_at")

        /** JSON map of store name -> epoch millis an item was last shopped there. */
        val STORE_LAST_SHOPPED = stringPreferencesKey("store_last_shopped")

        /** JSON list of store names in the user's manually chosen chip order. */
        val STORE_MANUAL_ORDER = stringPreferencesKey("store_manual_order")

        /** The shopping list's group-by-tag view toggle. */
        val GROUP_BY_TAGS = booleanPreferencesKey("group_by_tags")

        /** [RecipeSort] name: the recipe list's chosen sort. */
        val RECIPE_SORT = stringPreferencesKey("recipe_sort")

        /** Beta token for the hauly-backend extraction service; absent until set. */
        val BACKEND_TOKEN = stringPreferencesKey("hauly_backend_token")
    }

    val isConfigured: Flow<Boolean> = dataStore.data.map { it[Keys.CONFIGURED] ?: false }

    val storeOptions: Flow<List<String>> =
        dataStore.data.map { (it[Keys.STORE_OPTIONS] ?: emptySet()).sorted() }

    val tagOptions: Flow<List<String>> =
        dataStore.data.map { (it[Keys.TAG_OPTIONS] ?: emptySet()).sorted() }

    val lastSyncAt: Flow<Long?> = dataStore.data.map { it[Keys.LAST_SYNC] }

    /** When an item was last checked off per store; feeds the store-chip sort. */
    val storeLastShoppedAt: Flow<Map<String, Long>> = dataStore.data.map { prefs ->
        prefs[Keys.STORE_LAST_SHOPPED]?.let { raw ->
            runCatching { Json.decodeFromString<Map<String, Long>>(raw) }.getOrNull()
        } ?: emptyMap()
    }

    suspend fun recordStoreShopped(stores: List<String>, timestampMillis: Long) {
        if (stores.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[Keys.STORE_LAST_SHOPPED]?.let { raw ->
                runCatching { Json.decodeFromString<Map<String, Long>>(raw) }.getOrNull()
            } ?: emptyMap()
            prefs[Keys.STORE_LAST_SHOPPED] =
                Json.encodeToString(current + stores.associateWith { timestampMillis })
        }
    }

    /** The user's drag-reordered store chip order. Empty until they reorder once. */
    val storeManualOrder: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[Keys.STORE_MANUAL_ORDER]?.let { raw ->
            runCatching { Json.decodeFromString<List<String>>(raw) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun setStoreManualOrder(order: List<String>) {
        dataStore.edit { it[Keys.STORE_MANUAL_ORDER] = Json.encodeToString(order) }
    }

    /**
     * Whether the active list is grouped by tag. Applies to every store view and
     * survives restarts. Local-only, like the manual orders above.
     */
    val groupByTags: Flow<Boolean> = dataStore.data.map { it[Keys.GROUP_BY_TAGS] ?: false }

    suspend fun setGroupByTags(enabled: Boolean) {
        dataStore.edit { it[Keys.GROUP_BY_TAGS] = enabled }
    }

    /**
     * The recipe list's sort. Survives restarts; an unreadable stored value
     * (e.g. an enum constant removed in a later version) falls back to the
     * default rather than crashing.
     */
    val recipeSort: Flow<RecipeSort> = dataStore.data.map { prefs ->
        prefs[Keys.RECIPE_SORT]?.let { raw ->
            runCatching { RecipeSort.valueOf(raw) }.getOrNull()
        } ?: RecipeSort.ALPHA
    }

    suspend fun setRecipeSort(mode: RecipeSort) {
        dataStore.edit { it[Keys.RECIPE_SORT] = mode.name }
    }

    suspend fun token(): String? = dataStore.data.first()[Keys.TOKEN]

    /** Beta token for the hauly-backend extraction service; null when unset. */
    suspend fun backendToken(): String? = dataStore.data.first()[Keys.BACKEND_TOKEN]

    /** Whether a backend beta token is stored — gates the clipboard-extraction UI. */
    val hasBackendToken: Flow<Boolean> =
        dataStore.data.map { !it[Keys.BACKEND_TOKEN].isNullOrBlank() }

    /** Save (or, with a blank value, clear) the backend beta token. */
    suspend fun setBackendToken(token: String) {
        dataStore.edit {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) it.remove(Keys.BACKEND_TOKEN) else it[Keys.BACKEND_TOKEN] = trimmed
        }
    }

    suspend fun shoppingDatabaseId(): String? = dataStore.data.first()[Keys.SHOPPING_DB]

    suspend fun recipeDatabaseId(): String? = dataStore.data.first()[Keys.RECIPE_DB]

    suspend fun saveConfiguration(token: String, shoppingDbId: String, recipeDbId: String) {
        dataStore.edit {
            it[Keys.TOKEN] = token
            it[Keys.SHOPPING_DB] = shoppingDbId
            it[Keys.RECIPE_DB] = recipeDbId
            it[Keys.CONFIGURED] = true
        }
    }

    suspend fun saveSelectOptions(stores: Set<String>, tags: Set<String>) {
        dataStore.edit {
            it[Keys.STORE_OPTIONS] = stores
            it[Keys.TAG_OPTIONS] = tags
        }
    }

    suspend fun markSynced(timestampMillis: Long) {
        dataStore.edit { it[Keys.LAST_SYNC] = timestampMillis }
    }
}
