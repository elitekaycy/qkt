package com.qkt.marketdata.store.macro

import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches a daily macro series from FRED (Federal Reserve Economic Data) into [MacroSeriesStore].
 * Mirrors [com.qkt.marketdata.store.dukascopy.DukascopyTickFetcher] in shape: pull a range, parse,
 * persist. Hits `series/observations` JSON; FRED marks a missing day with the string `"."`, which
 * is dropped (the series simply has no value that day).
 *
 * An `api_key` is appended when [apiKey] is set (env `FRED_API_KEY`); low-volume use works without.
 */
class FredSeriesFetcher(
    private val store: MacroSeriesStore,
    private val apiKey: String? = System.getenv("FRED_API_KEY"),
    private val baseUrl: String = "https://api.stlouisfed.org/fred",
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun fetch(
        series: String,
        from: LocalDate,
        to: LocalDate,
    ) {
        val url =
            buildString {
                append("$baseUrl/series/observations?series_id=$series&file_type=json")
                append("&observation_start=$from&observation_end=$to")
                if (!apiKey.isNullOrBlank()) append("&api_key=$apiKey")
            }
        val body =
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                check(resp.isSuccessful) { "FRED fetch failed: HTTP ${resp.code} for $series" }
                resp.body?.string().orEmpty()
            }
        val observations =
            json
                .parseToJsonElement(body)
                .jsonObject["observations"]
                ?.jsonArray ?: return
        val points =
            observations.mapNotNull { el ->
                val o = el.jsonObject
                val date = o["date"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val value = o["value"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (value == ".") return@mapNotNull null // FRED missing-value marker
                MacroPoint(LocalDate.parse(date), BigDecimal(value))
            }
        store.write(series, points)
    }
}
