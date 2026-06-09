package com.qkt.marketdata.store.dukascopy

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
 * a transient timeout/IO failure a few times before giving up. Without this, the default
 * auto-fetch path fails on any slow response (okhttp's stock read timeout is only 10s).
 */
class OkHttpHourDownloader(
    private val baseUrl: String = "https://datafeed.dukascopy.com/datafeed",
    private val http: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(Duration.ofSeconds(20))
            .readTimeout(Duration.ofSeconds(60))
            .build(),
    private val maxAttempts: Int = 3,
) : HourDownloader {
    override fun download(
        instrument: String,
        day: LocalDate,
        hour: Int,
    ): ByteArray? {
        val mm = (day.monthValue - 1).toString().padStart(2, '0')
        val dd = day.dayOfMonth.toString().padStart(2, '0')
        val hh = hour.toString().padStart(2, '0')
        val url = "$baseUrl/$instrument/${day.year}/$mm/$dd/${hh}h_ticks.bi5"
        var lastError: IOException? = null
        repeat(maxAttempts) { attempt ->
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (resp.code == 404) return null
                    check(resp.isSuccessful) { "dukascopy fetch failed: HTTP ${resp.code} for $url" }
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    return if (bytes.isEmpty()) null else bytes
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt < maxAttempts - 1) Thread.sleep(500L * (attempt + 1))
            }
        }
        throw IOException("dukascopy fetch failed after $maxAttempts attempts for $url", lastError)
    }
}
