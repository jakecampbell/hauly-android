package com.jakecampbell.hauly.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ShoppingItemEntity::class,
        RecipeEntity::class,
        RecipeBlockEntity::class,
        RecipeItemCrossRef::class,
        RecipeLineMarkEntity::class,
        RecipeExtractionEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class HaulyDatabase : RoomDatabase() {
    abstract fun shoppingItemDao(): ShoppingItemDao
    abstract fun recipeDao(): RecipeDao
    abstract fun recipeExtractionDao(): RecipeExtractionDao

    companion object {
        /** v9 adds the device-local recipe_extractions table; purely additive. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recipe_extractions` (" +
                        "`id` TEXT NOT NULL, " +
                        "`source_text` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`ingredients` TEXT NOT NULL, " +
                        "`instructions` TEXT NOT NULL, " +
                        "`error` TEXT, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, " +
                        "`sync_status` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }
    }
}
