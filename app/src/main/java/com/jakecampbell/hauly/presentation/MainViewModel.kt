package com.jakecampbell.hauly.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jakecampbell.hauly.data.remote.NetworkActivityTracker
import com.jakecampbell.hauly.data.sync.SyncScheduler
import com.jakecampbell.hauly.domain.repository.OnboardingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    onboardingRepository: OnboardingRepository,
    networkActivityTracker: NetworkActivityTracker,
    syncScheduler: SyncScheduler,
) : ViewModel() {

    /** null while the persisted configuration is still being read. */
    val isConfigured: StateFlow<Boolean?> = onboardingRepository.isConfigured
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // Sync on every app start: repopulates the cache after a schema wipe
        // and picks up changes made from Notion on other devices.
        viewModelScope.launch {
            if (onboardingRepository.isConfigured.first()) {
                syncScheduler.requestSync()
            }
        }
    }

    /** True while any Notion request is in flight (foreground or background sync). */
    val isNetworkBusy: StateFlow<Boolean> = networkActivityTracker.isBusy
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}
