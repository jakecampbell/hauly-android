package com.jakecampbell.hauly.data.remote

import com.jakecampbell.hauly.data.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import kotlin.math.min
import kotlin.random.Random

const val NOTION_VERSION = "2022-06-28"
const val NOTION_BASE_URL = "https://api.notion.com/v1/"

/**
 * Reports request lifecycles to the [NetworkActivityTracker]. Placed outermost
 * in the chain so one logical call (including retry/backoff time spent in
 * [RetryAfterInterceptor]) counts as a single continuous span of activity.
 */
class NetworkActivityInterceptor(
    private val tracker: NetworkActivityTracker,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        tracker.begin()
        try {
            return chain.proceed(chain.request())
        } finally {
            tracker.end()
        }
    }
}

/** Injects the required `Notion-Version` and `Authorization` headers into every request. */
class NotionHeadersInterceptor(
    private val settings: SettingsRepository,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
            .header("Notion-Version", NOTION_VERSION)

        // Requests carrying an explicit Authorization header (onboarding validates
        // a token before it is persisted) keep it; everything else uses the saved PAT.
        if (chain.request().header("Authorization") == null) {
            val token = runBlocking { settings.token() }
            if (token != null) {
                builder.header("Authorization", "Bearer $token")
            }
        }
        return chain.proceed(builder.build())
    }
}

/**
 * Retries 429 (rate limit) and transient 5xx responses with exponential
 * backoff + jitter, honoring Notion's `Retry-After` header when present.
 */
class RetryAfterInterceptor(
    private val maxAttempts: Int = 5,
    private val baseDelayMillis: Long = 1_000,
    private val maxDelayMillis: Long = 30_000,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        var lastException: IOException? = null
        while (attempt < maxAttempts) {
            val response = try {
                chain.proceed(chain.request())
            } catch (e: IOException) {
                lastException = e
                attempt++
                if (attempt >= maxAttempts) throw e
                sleep(backoffMillis(attempt))
                continue
            }

            if (response.code != 429 && response.code !in 500..504) {
                return response
            }

            attempt++
            if (attempt >= maxAttempts) {
                return response
            }

            val retryAfterMillis = response.header("Retry-After")
                ?.toLongOrNull()
                ?.times(1_000)
            response.close()
            sleep(retryAfterMillis ?: backoffMillis(attempt))
        }
        throw lastException ?: IOException("Retry attempts exhausted")
    }

    private fun backoffMillis(attempt: Int): Long {
        val exponential = baseDelayMillis * (1L shl (attempt - 1))
        val jitter = Random.nextLong(0, baseDelayMillis)
        return min(exponential + jitter, maxDelayMillis)
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while backing off", e)
        }
    }
}
