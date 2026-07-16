package com.jakecampbell.hauly.di

import com.jakecampbell.hauly.BuildConfig
import com.jakecampbell.hauly.data.remote.NOTION_BASE_URL
import com.jakecampbell.hauly.data.remote.NetworkActivityInterceptor
import com.jakecampbell.hauly.data.remote.NetworkActivityTracker
import com.jakecampbell.hauly.data.remote.NotionApi
import com.jakecampbell.hauly.data.remote.NotionHeadersInterceptor
import com.jakecampbell.hauly.data.remote.RetryAfterInterceptor
import com.jakecampbell.hauly.data.remote.backend.BackendAuthInterceptor
import com.jakecampbell.hauly.data.remote.backend.HaulyBackendApi
import com.jakecampbell.hauly.data.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** The OkHttp client for the hauly-backend; the unqualified client is Notion's. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HaulyBackend

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        settings: SettingsRepository,
        networkActivityTracker: NetworkActivityTracker,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(NetworkActivityInterceptor(networkActivityTracker))
            .addInterceptor(NotionHeadersInterceptor(settings))
            .addInterceptor(RetryAfterInterceptor())
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                            redactHeader("Authorization")
                        }
                    )
                }
            }
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideNotionApi(client: OkHttpClient, json: Json): NotionApi =
        Retrofit.Builder()
            .baseUrl(NOTION_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(NotionApi::class.java)

    /**
     * Separate client for the hauly-backend: no Notion headers (the PAT must
     * never leave for another host) and no NetworkActivityInterceptor — the
     * global progress bar is scoped to Notion traffic (R9.2), and the 2s
     * extraction poll has its own indicator (the pulsing row). The backoff
     * interceptor is reused with fewer attempts so a poll tick fails fast.
     */
    @Provides
    @Singleton
    @HaulyBackend
    fun provideBackendOkHttpClient(settings: SettingsRepository): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(BackendAuthInterceptor(settings))
            .addInterceptor(RetryAfterInterceptor(maxAttempts = 3))
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                            redactHeader("Authorization")
                        }
                    )
                }
            }
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideHaulyBackendApi(@HaulyBackend client: OkHttpClient, json: Json): HaulyBackendApi =
        Retrofit.Builder()
            .baseUrl(BuildConfig.HAULY_BACKEND_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(HaulyBackendApi::class.java)
}
