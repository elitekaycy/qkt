package com.qkt.marketdata.store.dukascopy

import com.qkt.common.Clock
import com.qkt.common.SystemClock
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import okhttp3.OkHttpClient
import okhttp3.Request

/** Downloads one dukascopy hour file, or null when the hour has no file (404). */
interface HourDownloader {
    fun download(
        instrument: String,
        day: LocalDate,
        hour: Int,
    ): ByteArray?
}

/**
 * okhttp-backed [HourDownloader] against the dukascopy datafeed. The URL month is zero-indexed
 * (January = `00`), matching dukascopy's path scheme.
 *
 * e.g. `download("XAUUSD", 2024-03-05, 9)` →
 * `https://datafeed.dukascopy.com/datafeed/XAUUSD/2024/02/05/09h_ticks.bi5`.
 *
 * The dukascopy CDN is routinely slow — a single hour file can take 15-20s to start responding.
 * A backtest fetches 24 of them per day, so the client uses a generous read timeout and retries
 * a transient failure (timeout, IO, HTTP 429 or 5xx) with exponential backoff (1s, 2s, 4s, 8s, ...),
 * honoring `Retry-After`, before failing over to the next host and only then giving up. Without
 * this, the default auto-fetch path fails on any slow response (okhttp's stock read timeout is
 * only 10s) or brief rate-limit window.
 */
class OkHttpHourDownloader(
    private val baseUrl: String = "https://datafeed.dukascopy.com/datafeed",
    private val http: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(Duration.ofSeconds(20))
            .readTimeout(Duration.ofSeconds(60))
            .build(),
    private val maxAttempts: Int = 5,
    /**
     * Fallback hosts tried after [baseUrl] exhausts its attempts, same path scheme
     * (`jetta` routinely answers 200 while the main feed rate-limits, #1375).
     */
    private val fallbackBaseUrls: List<String> = listOf("https://jetta.dukascopy.com/datafeed"),
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: Clock = SystemClock(),
) : HourDownloader {
    override fun download(
        instrument: String,
        day: LocalDate,
        hour: Int,
    ): ByteArray? {
        val mm = (day.monthValue - 1).toString().padStart(2, '0')
        val dd = day.dayOfMonth.toString().padStart(2, '0')
        val hh = hour.toString().padStart(2, '0')
        val path = "$instrument/${day.year}/$mm/$dd/${hh}h_ticks.bi5"
        val hosts = listOf(baseUrl) + fallbackBaseUrls
        var lastError: IOException? = null
        for (base in hosts) {
            val url = "$base/$path"
            var attempt = 0
            while (attempt < maxAttempts) {
                try {
                    http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                        // No file for this hour: a fact, not a failure — never fail over on it.
                        if (resp.code == 404) return null
                        if (resp.code == 429 || resp.code >= 500) {
                            throw RetryableFetch("HTTP ${resp.code}", retryAfterMs(resp))
                        }
                        check(resp.isSuccessful) { "dukascopy fetch failed: HTTP ${resp.code} for $url" }
                        val bytes = resp.body?.bytes() ?: ByteArray(0)
                        return if (bytes.isEmpty()) null else bytes
                    }
                } catch (e: RetryableFetch) {
                    lastError = IOException("HTTP ${e.message} for $url", e)
                    if (attempt < maxAttempts - 1) sleeper(backoffMs(attempt, e.retryAfterMs))
                } catch (e: IOException) {
                    lastError = e
                    if (attempt < maxAttempts - 1) sleeper(backoffMs(attempt, null))
                }
                attempt++
            }
        }
        throw IOException("dukascopy fetch failed after $maxAttempts attempts per host for $path", lastError)
    }

    /**
     * Exponential backoff with a ceiling that outlasts rate-limit windows (1s, 2s, 4s, 8s, ...),
     * honoring the server's `Retry-After` (seconds or HTTP date) when it asks for longer.
     */
    private fun backoffMs(
        attempt: Int,
        retryAfterMs: Long?,
    ): Long {
        val exponential = 1000L * (1L shl attempt.coerceAtMost(10))
        return maxOf(exponential, (retryAfterMs ?: 0L).coerceAtMost(MAX_BACKOFF_MS))
    }

    private fun retryAfterMs(resp: okhttp3.Response): Long? {
        val raw = resp.header("Retry-After")?.trim() ?: return null
        raw.toLongOrNull()?.let { return (it * 1000L).coerceAtLeast(0L) }
        return runCatching {
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .parse(raw, java.time.Instant::from)
                .toEpochMilli() - clock.now()
        }.getOrNull()?.coerceAtLeast(0L)
    }

    private class RetryableFetch(
        message: String,
        val retryAfterMs: Long?,
    ) : IOException(message)

    companion object {
        const val MAX_BACKOFF_MS: Long = 60_000L
    }
}
