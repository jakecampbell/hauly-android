package com.jakecampbell.hauly.di

import com.jakecampbell.hauly.data.repository.OnboardingRepositoryImpl
import com.jakecampbell.hauly.data.repository.RecipeExtractionRepositoryImpl
import com.jakecampbell.hauly.data.repository.RecipeRepositoryImpl
import com.jakecampbell.hauly.data.repository.ShoppingRepositoryImpl
import com.jakecampbell.hauly.domain.repository.OnboardingRepository
import com.jakecampbell.hauly.domain.repository.RecipeExtractionRepository
import com.jakecampbell.hauly.domain.repository.RecipeRepository
import com.jakecampbell.hauly.domain.repository.ShoppingRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindShoppingRepository(impl: ShoppingRepositoryImpl): ShoppingRepository

    @Binds
    @Singleton
    abstract fun bindRecipeRepository(impl: RecipeRepositoryImpl): RecipeRepository

    @Binds
    @Singleton
    abstract fun bindOnboardingRepository(impl: OnboardingRepositoryImpl): OnboardingRepository

    @Binds
    @Singleton
    abstract fun bindRecipeExtractionRepository(
        impl: RecipeExtractionRepositoryImpl,
    ): RecipeExtractionRepository
}
