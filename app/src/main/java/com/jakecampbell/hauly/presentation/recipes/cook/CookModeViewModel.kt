package com.jakecampbell.hauly.presentation.recipes.cook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * App-root view of cook mode: whether any recipe is cooking (to keep the screen
 * awake, R8.18), the stream of countdown completions (to sound the tone), and
 * whether any finished timer is still unacknowledged (to keep vibrating until it
 * is reset). Observed once, high up in the navigation host (R8.20).
 */
@HiltViewModel
class CookModeViewModel @Inject constructor(
    controller: CookModeController,
) : ViewModel() {

    val anyCooking: StateFlow<Boolean> = controller.sessions
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** True while any timer sits finished-but-not-reset — drives the vibration. */
    val anyFinished: StateFlow<Boolean> = controller.sessions
        .map { sessions ->
            sessions.values.any { session ->
                session.overall.finished || session.steps.values.any { it.finished }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val completions: SharedFlow<TimerKey> = controller.completions
}
