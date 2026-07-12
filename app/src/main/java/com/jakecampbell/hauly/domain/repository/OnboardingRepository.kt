package com.jakecampbell.hauly.domain.repository

import com.jakecampbell.hauly.domain.model.SetupValidation
import kotlinx.coroutines.flow.Flow

interface OnboardingRepository {

    /** True once a PAT + both database ids have been validated and saved. */
    val isConfigured: Flow<Boolean>

    /**
     * Verify the token can reach both databases and that they contain the exact
     * required schemas. Persists the configuration only when validation passes.
     */
    suspend fun validateAndSave(
        token: String,
        shoppingDatabaseId: String,
        recipeDatabaseId: String,
    ): SetupValidation
}
