package com.jakecampbell.hauly.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ShoppingItemEntity::class,
        RecipeEntity::class,
        RecipeBlockEntity::class,
        RecipeItemCrossRef::class,
        RecipeLineMarkEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class HaulyDatabase : RoomDatabase() {
    abstract fun shoppingItemDao(): ShoppingItemDao
    abstract fun recipeDao(): RecipeDao
}
