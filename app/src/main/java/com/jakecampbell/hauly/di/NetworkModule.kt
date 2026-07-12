package com.jakecampbell.hauly.di

import com.jakecampbell.hauly.BuildConfig
import com.jakecampbell.hauly.data.remote.NOTION_BASE_URL
import com.jakecampbell.hauly.data.remote.NetworkActivityInterceptor
import com.jakecampbell.hauly.data.remote.NetworkActivityTracker
import com.jakecampbell.hauly.data.remote.NotionApi
import com.jakecampbell.hauly.data.remote.NotionHeadersInterceptor
import com.jakecampbell.hauly.data.remote.RetryAfterInterceptor
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
import javax.inject.Singleton

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
}
