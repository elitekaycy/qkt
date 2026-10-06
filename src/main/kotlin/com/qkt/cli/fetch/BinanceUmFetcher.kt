package com.qkt.cli.fetch

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.store.binance.BinanceKlineCsv
import com.qkt.marketdata.store.binance.BinanceQuarterly
import com.qkt.marketdata.store.binance.BinanceVisionClient
import java.time.Instant
import java.time.ZoneOffset

/**
 * `qkt fetch BINANCE_UM:<contract>`: one Binance daily kline file per UTC day. A day with no file
 * is empty; it is expected to be empty only after the contract has delivered.
 */
internal class BinanceUmFetcher(
    private val client: BinanceVisionClient,
) : BarFetcher {
    override fun fetch(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): List<Candle> {
        val interval =
            INTERVALS[window.durationMs]
                ?: error("Binance publishes ${INTERVALS.values} klines, not ${window.durationMs} ms")
        val day = range.from.atZone(ZoneOffset.UTC).toLocalDate()
        val zip =
            client.download("data/futures/um/daily/klines/$symbol/$interval/$symbol-$interval-$day.zip")
                ?: return emptyList()
        val text = client.unzipSingle(zip) ?: return emptyList()
        // Binance keeps printing flat, zero-volume klines after delivery; nothing from expiry on is tradeable.
        val end = minOf(range.to.toEpochMilli(), BinanceQuarterly.expiryMs(symbol) ?: Long.MAX_VALUE)
        return BinanceKlineCsv
            .parse("$VENUE:$symbol", text, window)
            .filter { it.startTime >= range.from.toEpochMilli() && it.startTime < end }
    }

    override fun isExpectedEmpty(
        symbol: String,
        range: TimeRange,
    ): Boolean {
        val expiry = BinanceQuarterly.expiryMs(symbol) ?: return false
        return range.from.isAfter(Instant.ofEpochMilli(expiry))
    }

    companion object {
        const val VENUE: String = "BINANCE_UM"
        private val INTERVALS: Map<Long, String> =
            linkedMapOf(
                60_000L to "1m",
                180_000L to "3m",
                300_000L to "5m",
                900_000L to "15m",
                1_800_000L to "30m",
                3_600_000L to "1h",
                7_200_000L to "2h",
                14_400_000L to "4h",
                21_600_000L to "6h",
                28_800_000L to "8h",
                43_200_000L to "12h",
                86_400_000L to "1d",
            )
    }
}
