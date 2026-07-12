package com.jakecampbell.hauly.data.local

import androidx.room.TypeConverter
import com.jakecampbell.hauly.domain.model.SyncStatus
import kotlinx.serialization.json.Json

/** Room type converters. Lists are stored as JSON arrays. */
class Converters {

    private val json = Json

    @TypeConverter
    fun fromStringList(value: List<String>): String = json.encodeToString(value)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else json.decodeFromString(value)

    @TypeConverter
    fun fromSyncStatus(value: SyncStatus): String = value.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)
}
