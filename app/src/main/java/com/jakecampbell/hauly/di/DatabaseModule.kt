package com.jakecampbell.hauly.di

import android.content.Context
import androidx.room.Room
import com.jakecampbell.hauly.data.local.HaulyDatabase
import com.jakecampbell.hauly.data.local.RecipeDao
import com.jakecampbell.hauly.data.local.ShoppingItemDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): HaulyDatabase =
        Room.databaseBuilder(context, HaulyDatabase::class.java, "hauly.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideShoppingItemDao(db: HaulyDatabase): ShoppingItemDao = db.shoppingItemDao()

    @Provides
    fun provideRecipeDao(db: HaulyDatabase): RecipeDao = db.recipeDao()
}
