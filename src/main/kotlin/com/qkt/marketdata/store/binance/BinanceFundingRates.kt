package com.qkt.marketdata.store.binance

import com.qkt.instrument.FundingRate
import com.qkt.instrument.FundingRateSource
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Binance USDⓈ-M perpetuals' funding history from the public futures API (`/fapi/v1/fundingRate`, no
 * key), paged a thousand payments at a time. Each payment is `fundingRate` at `markPrice`, every 8 hours
 * for most symbols; old payments carry an empty `markPrice`, read as none. `BINANCE_UM:BTCUSDT` is the
 * perpetual `BTCUSDT`.
 */
class BinanceFundingRates(
    private val apiBaseUrl: String = BinanceVisionClient.API_BASE,
    private val http: OkHttpClient = OkHttpClient(),
) : FundingRateSource {
    override fun rates(
        qktSymbol: String,
        fromMs: Long,
        toMs: Long,
    ): List<FundingRate> {
        val symbol = qktSymbol.substringAfter(':')
        val rates = ArrayList<FundingRate>()
        var start = fromMs
        while (start <= toMs) {
            val page = page(symbol, start, toMs)
            rates += page
            if (page.size < PAGE) break
            start = page.last().timeMs + 1
        }
        return rates
    }

    private fun page(
        symbol: String,
        fromMs: Long,
        toMs: Long,
    ): List<FundingRate> {
        val url = "$apiBaseUrl/fapi/v1/fundingRate?symbol=$symbol&startTime=$fromMs&endTime=$toMs&limit=$PAGE"
        val body =
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code} reading Binance funding rates of $symbol: ${resp.body?.string()?.take(200)}" }
                resp.body?.string() ?: error("empty funding-rate body for $symbol")
            }
        return parse(body)
    }

    companion object {
        private const val PAGE = 1000

        /** One `/fapi/v1/fundingRate` answer, each number from its exact text. */
        fun parse(body: String): List<FundingRate> =
            Json.parseToJsonElement(body).jsonArray.map { row ->
                val o = row.jsonObject
                val text = { key: String -> (o.getValue(key) as JsonPrimitive).content }
                FundingRate(text("fundingTime").toLong(), BigDecimal(text("fundingRate")), text("markPrice").takeIf { it.isNotEmpty() }?.let(::BigDecimal))
            }
    }
}
