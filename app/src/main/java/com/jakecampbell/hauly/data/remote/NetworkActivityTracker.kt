package com.jakecampbell.hauly.data.remote

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Counts in-flight Notion requests (fed by an OkHttp interceptor, so every
 * fetch/update — including background WorkManager syncs — is captured) and
 * exposes a busy signal for the global activity indicator.
 */
@Singleton
class NetworkActivityTracker @Inject constructor() {

    private val activeRequests = MutableStateFlow(0)

    /**
     * True while any request is in flight. Turning busy is instant; turning
     * idle is held briefly so back-to-back paginated calls read as one
     * continuous operation instead of flickering.
     */
    @OptIn(FlowPreview::class)
    val isBusy: Flow<Boolean> = activeRequests
        .map { it > 0 }
        .distinctUntilChanged()
        .debounce { busy -> if (busy) 0L else 300L }
        .distinctUntilChanged()

    fun begin() = activeRequests.update { it + 1 }

    fun end() = activeRequests.update { (it - 1).coerceAtLeast(0) }
}
