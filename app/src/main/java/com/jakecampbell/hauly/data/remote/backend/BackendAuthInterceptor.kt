package com.jakecampbell.hauly.data.remote.backend

import com.jakecampbell.hauly.data.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Injects the user's hauly-backend beta token as a Bearer `Authorization`
 * header. Same runBlocking-on-OkHttp-thread trade-off as
 * [com.jakecampbell.hauly.data.remote.NotionHeadersInterceptor].
 */
class BackendAuthInterceptor(
    private val settings: SettingsRepository,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { settings.backendToken() }
        val request = if (token.isNullOrBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(request)
    }
}
