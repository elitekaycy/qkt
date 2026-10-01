package com.qkt.marketdata.store.deribit

import java.io.IOException
import java.time.Duration
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
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
 * over 100 MB. A throttled request (HTTP 429) is retried after a growing pause; a JSON-RPC error fails
 * with the venue's message.
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

    @OptIn(ExperimentalSerializationApi::class)
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
        for (attempt in 1..MAX_ATTEMPTS) {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.code == HTTP_TOO_MANY_REQUESTS) {
                    sleep(BACKOFF_MS * attempt)
                } else {
                    if (!response.isSuccessful) throw IOException("Deribit HTTP ${response.code} for $method")
                    val stream = response.body?.byteStream() ?: throw IOException("Deribit $method returned no body")
                    val envelope = json.decodeFromStream(DeribitEnvelope.serializer(result), stream)
                    envelope.error?.let { throw IOException("Deribit $method failed: ${it.message}") }
                    return envelope.result ?: throw IOException("Deribit $method returned no result")
                }
            }
        }
        throw IOException("Deribit throttled $method $MAX_ATTEMPTS times in a row")
    }

    companion object {
        /** The venue prefix of Deribit symbols in qkt. */
        const val VENUE = "DERIBIT"
        private const val LIVE_BASE = "https://www.deribit.com/api/v2"
        private const val HISTORY_BASE = "https://history.deribit.com/api/v2"
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val MAX_ATTEMPTS = 5
        private const val BACKOFF_MS = 1_000L
    }
}
