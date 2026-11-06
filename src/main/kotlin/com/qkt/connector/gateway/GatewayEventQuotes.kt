package com.qkt.connector.gateway

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

private val eventQuoteJson = Json { ignoreUnknownKeys = true }

/**
 * The latest quote of each of [codes], contracts of one event family, for a one-shot read (`qkt bot chain`, a slot
 * resolved at call time): one quotes subscription by the family's [series] (`roots=KXINXU`), held until every code has
 * quoted or [waitMs] has passed. An event contract's book may be one-sided or empty, so a quote is kept as it comes;
 * a code that never quoted within the wait is absent from the answer.
 */
internal fun gatewayEventQuotes(
    baseUrl: String,
    apiKey: String,
    series: String,
    codes: Set<String>,
    waitMs: Long,
): Map<String, WireQuote> {
    val url =
        baseUrl
            .toHttpUrl()
            .newBuilder()
            .addPathSegments("v1/quotes")
            .addQueryParameter("roots", series)
            .build()
            .toString()
    val latest = ConcurrentHashMap<String, WireQuote>()
    val all = CountDownLatch(1)
    val socket =
        GatewaySocket(
            "event-quotes",
            apiKey,
            { url },
            onText = { text ->
                val quote = runCatching { eventQuoteJson.decodeFromString(WireQuote.serializer(), text) }.getOrNull()
                if (quote != null && quote.symbol in codes) latest[quote.symbol] = quote
                if (latest.keys.containsAll(codes)) all.countDown()
            },
            onConnection = { _, _ -> },
            initialBackoffMs = 500,
            maxBackoffMs = 2_000,
        )
    socket.start()
    try {
        all.await(waitMs, TimeUnit.MILLISECONDS)
    } finally {
        socket.stop()
    }
    return latest.toMap()
}
