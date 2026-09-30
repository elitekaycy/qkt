package com.qkt.marketdata.store.binance

import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Candle

/**
 * Binance kline CSV (`open_time,open,high,low,close,volume,close_time,…`). Files before 2022 have no
 * header row; both forms parse the same. `close_time` is inclusive (`open + span − 1`), so a row is
 * checked against [window] and becomes a candle ending exclusively at `open + span`.
 */
object BinanceKlineCsv {
    /** Every kline row in [text] as a candle of [symbol]. */
    fun parse(
        symbol: String,
        text: String,
        window: TimeWindow,
    ): List<Candle> =
        text
            .lineSequence()
            .filter { it.isNotBlank() && it[0].isDigit() }
            .map { row(symbol, it, window) }
            .toList()

    private fun row(
        symbol: String,
        line: String,
        window: TimeWindow,
    ): Candle {
        val f = line.split(',')
        require(f.size >= FIELDS) { "kline row has ${f.size} fields, expected $FIELDS: $line" }
        val open = f[0].toLong()
        require(f[6].toLong() + 1 - open == window.durationMs) {
            "kline row at $open spans ${f[6].toLong() + 1 - open} ms, expected ${window.durationMs}"
        }
        return Candle(
            symbol = symbol,
            open = f[1].toBigDecimal(),
            high = f[2].toBigDecimal(),
            low = f[3].toBigDecimal(),
            close = f[4].toBigDecimal(),
            volume = f[5].toBigDecimal(),
            startTime = open,
            endTime = open + window.durationMs,
        )
    }

    private const val FIELDS = 7
}
