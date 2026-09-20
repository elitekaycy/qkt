package com.qkt.connector.mt5

import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory

/**
 * Gateway GETs: retried with backoff, and snapshot reads (account, positions, orders) served from
 * the shared [MT5ReadCache] so sibling strategies on one account do not repeat an identical call.
 */
internal class MT5GatewayReads(
    private val http: OkHttpClient,
    private val gatewayUrl: String,
    private val apiKey: String?,
    private val retryAttempts: Int,
    private val readCache: MT5ReadCache?,
) {
    private val log = LoggerFactory.getLogger(MT5Client::class.java)
    private val lastReadFailureRef = AtomicReference<String?>(null)

    /** The body of a successful GET of [url], or null after every retry failed. */
    fun get(url: String): String? {
        val cache = readCache
        return if (cache != null && isSnapshotRead(url)) {
            cache.get(url) { getFromNetworkWithRetry(url) }
        } else {
            getFromNetworkWithRetry(url)
        }
    }

    private fun isSnapshotRead(url: String): Boolean =
        when (url.substringAfter(gatewayUrl)) {
            "/account", "/get_positions", "/orders" -> true
            else ->
                url.startsWith("$gatewayUrl/get_positions?") ||
                    url.startsWith("$gatewayUrl/orders?")
        }

    private fun getFromNetworkWithRetry(url: String): String? {
        var attempt = 0
        var failure: String? = null
        while (attempt <= retryAttempts) {
            try {
                val resp = http.newCall(mt5RequestBuilder(url, apiKey).build()).execute()
                resp.use {
                    val raw = it.body?.string().orEmpty()
                    if (it.isSuccessful) {
                        lastReadFailureRef.set(null)
                        return raw
                    }
                    failure = "HTTP ${it.code}: $raw"
                }
            } catch (e: java.io.IOException) {
                failure = "IO error: ${e.message}"
            }
            attempt++
            if (attempt <= retryAttempts) Thread.sleep(200L * attempt)
        }
        lastReadFailureRef.set(failure ?: "gateway read failed")
        // Message-only: a refused/timed-out GET after retries is an expected operational
        // condition; the okhttp stack adds no signal and floods test output (#879).
        log.warn("MT5Client GET $url failed after $retryAttempts retries: {}", lastReadFailureRef.get())
        return null
    }

    /** Detail from the most recent failed GET, cleared by the next successful network read. */
    fun lastReadFailure(): String? = lastReadFailureRef.get()
}
