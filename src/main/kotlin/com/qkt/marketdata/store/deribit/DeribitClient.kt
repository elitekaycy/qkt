package com.qkt.marketdata.store.deribit

import java.io.IOException
import java.time.Duration
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Deribit's public JSON-RPC API over HTTPS GET, no key: the live host for current instruments and
 * index data, the history host (`history.deribit.com`) for expired instruments. Responses are decoded
 * as they stream in, into small records that ignore unused fields — the expired-options listing is
 * over 100 MB. Throttling (HTTP 429), server errors and lost connections are retried after a growing
 * pause, up to five attempts; a JSON-RPC error (sent with HTTP 400) fails at once with the venue's
 * message and reason.
 */
class DeribitClient(
    private val liveBaseUrl: String = LIVE_BASE,
    private val historyBaseUrl: String = HISTORY_BASE,
    /** Records asked for per page by paged endpoints. */
    val pageSize: Int = 1000,
    private val http: OkHttpClient = OkHttpClient.Builder().readTimeout(Duration.ofMinutes(5)).build(),
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private val json = Json { ignoreUnknownKeys = true }

    internal fun instruments(
        expired: Boolean,
        params: Map<String, String>,
    ): List<DeribitInstrument> =
        call(
            if (expired) historyBaseUrl else liveBaseUrl,
            "get_instruments",
            params + ("expired" to "$expired"),
            ListSerializer(DeribitInstrument.serializer()),
        )

    internal fun deliveryPrices(
        index: String,
        offset: Int,
    ): DeribitDeliveryPage =
        call(
            liveBaseUrl,
            "get_delivery_prices",
            mapOf("index_name" to index, "offset" to "$offset", "count" to "$pageSize"),
            DeribitDeliveryPage.serializer(),
        )

    private fun <T> call(
        base: String,
        method: String,
        params: Map<String, String>,
        result: KSerializer<T>,
    ): T {
        val url =
            "$base/public/$method"
                .toHttpUrl()
                .newBuilder()
                .apply {
                    params.forEach { (k, v) ->
                        addQueryParameter(k, v)
                    }
                }.build()
        var lastFailure = IOException("Deribit $method was not attempted")
        for (attempt in 1..MAX_ATTEMPTS) {
            when (val outcome = attempt(url, method, result)) {
                is Outcome.Done -> return outcome.value
                is Outcome.Fail -> throw outcome.cause
                is Outcome.Retry -> {
                    lastFailure = outcome.cause
                    if (attempt < MAX_ATTEMPTS) sleep(BACKOFF_MS * attempt)
                }
            }
        }
        throw lastFailure
    }

    /**
     * One request: throttling (429), server errors (5xx) and a connection lost mid-stream are worth
     * retrying; a JSON-RPC error is not — it is decoded from the body whatever the status.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun <T> attempt(
        url: okhttp3.HttpUrl,
        method: String,
        result: KSerializer<T>,
    ): Outcome<T> =
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val code = response.code
                if (code == HTTP_TOO_MANY_REQUESTS || code >= HTTP_SERVER_ERROR) {
                    return Outcome.Retry(IOException("Deribit HTTP $code for $method"))
                }
                val envelope =
                    response.body?.byteStream()?.let { stream ->
                        try {
                            json.decodeFromStream(DeribitEnvelope.serializer(result), stream)
                        } catch (e: SerializationException) {
                            null
                        }
                    }
                envelope?.error?.let { return Outcome.Fail(IOException("Deribit $method failed: ${it.description}")) }
                if (!response.isSuccessful) return Outcome.Fail(IOException("Deribit HTTP $code for $method"))
                envelope?.result?.let { Outcome.Done(it) }
                    ?: Outcome.Fail(IOException("Deribit $method returned no result"))
            }
        } catch (e: IOException) {
            Outcome.Retry(e)
        }

    private sealed interface Outcome<out T> {
        data class Done<T>(
            val value: T,
        ) : Outcome<T>

        data class Retry(
            val cause: IOException,
        ) : Outcome<Nothing>

        data class Fail(
            val cause: IOException,
        ) : Outcome<Nothing>
    }

    companion object {
        /** The venue prefix of Deribit symbols in qkt. */
        const val VENUE = "DERIBIT"
        private const val LIVE_BASE = "https://www.deribit.com/api/v2"
        private const val HISTORY_BASE = "https://history.deribit.com/api/v2"
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500
        private const val MAX_ATTEMPTS = 5
        private const val BACKOFF_MS = 1_000L
    }
}
