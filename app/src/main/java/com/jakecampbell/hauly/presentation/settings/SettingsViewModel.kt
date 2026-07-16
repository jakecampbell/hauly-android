package com.jakecampbell.hauly.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jakecampbell.hauly.data.settings.SettingsRepository
import com.jakecampbell.hauly.data.sync.ConnectivityObserver
import com.jakecampbell.hauly.data.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class SettingsUiState(
    val shoppingDatabaseId: String = "",
    val recipeDatabaseId: String = "",
    val lastSyncLabel: String? = null,
    val isOnline: Boolean = true,
    val syncRequested: Boolean = false,
    /** Whether a hauly-backend beta token is stored (the token itself never leaves DataStore). */
    val betaTokenSet: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    private val syncRequested = MutableStateFlow(false)

    private val ids = MutableStateFlow(Pair("", ""))

    init {
        viewModelScope.launch {
            ids.value = Pair(
                settings.shoppingDatabaseId() ?: "",
                settings.recipeDatabaseId() ?: "",
            )
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        ids,
        settings.lastSyncAt,
        connectivityObserver.isOnline,
        syncRequested,
        settings.hasBackendToken,
    ) { (shoppingId, recipeId), lastSync, online, requested, betaTokenSet ->
        SettingsUiState(
            shoppingDatabaseId = shoppingId,
            recipeDatabaseId = recipeId,
            lastSyncLabel = lastSync?.let {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
            },
            isOnline = online,
            syncRequested = requested,
            betaTokenSet = betaTokenSet,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun syncNow() {
        syncScheduler.requestSync()
        syncRequested.value = true
    }

    /** Save (or, with a blank value, clear) the hauly-backend beta token. */
    fun saveBetaToken(token: String) {
        viewModelScope.launch { settings.setBackendToken(token) }
    }
}
