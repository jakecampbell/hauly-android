package com.jakecampbell.hauly.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jakecampbell.hauly.domain.model.SetupValidation
import com.jakecampbell.hauly.domain.repository.OnboardingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val token: String = "",
    val shoppingDatabaseId: String = "",
    val recipeDatabaseId: String = "",
    val isValidating: Boolean = false,
    /** Human-readable validation problems; empty when nothing failed yet. */
    val problems: List<String> = emptyList(),
    val completed: Boolean = false,
) {
    val canSubmit: Boolean
        get() = !isValidating &&
            token.isNotBlank() && shoppingDatabaseId.isNotBlank() && recipeDatabaseId.isNotBlank()
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val onboardingRepository: OnboardingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun onTokenChange(value: String) = _uiState.update { it.copy(token = value) }

    fun onShoppingDbChange(value: String) = _uiState.update { it.copy(shoppingDatabaseId = value) }

    fun onRecipeDbChange(value: String) = _uiState.update { it.copy(recipeDatabaseId = value) }

    fun validate() {
        val state = _uiState.value
        if (!state.canSubmit) return
        _uiState.update { it.copy(isValidating = true, problems = emptyList()) }
        viewModelScope.launch {
            val result = onboardingRepository.validateAndSave(
                token = state.token,
                shoppingDatabaseId = state.shoppingDatabaseId,
                recipeDatabaseId = state.recipeDatabaseId,
            )
            _uiState.update {
                when (result) {
                    is SetupValidation.Valid ->
                        it.copy(isValidating = false, completed = true)

                    is SetupValidation.Invalid ->
                        it.copy(
                            isValidating = false,
                            problems = result.problems.map { p -> p.describe() },
                        )

                    is SetupValidation.Failed ->
                        it.copy(isValidating = false, problems = listOf(result.message))
                }
            }
        }
    }
}
