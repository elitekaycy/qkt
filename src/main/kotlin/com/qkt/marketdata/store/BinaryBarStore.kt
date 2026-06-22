package com.qkt.marketdata.store

import com.qkt.candles.TimeWindow
import com.qkt.marketdata.BinaryBarFeed
import com.qkt.marketdata.BinaryBarWriter
import com.qkt.marketdata.Candle
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * On-disk binary bar store: `bars/<broker>/<symbol>/<tf>/<YYYY-MM-DD>.bin`, one UTC day per file.
 * File presence IS coverage (no separate manifest — bars are cheap to rebuild). Written once by
 * `qkt data build-bars` (decode ticks -> aggregate -> store); read by the `--bars` replay path.
 */
class BinaryBarStore(
    private val root: Path,
) {
    private fun dayFile(
        broker: String,
        symbol: String,
        tf: TimeWindow,
        date: LocalDate,
    ): Path =
        root
            .resolve("bars")
            .resolve(broker)
            .resolve(symbol)
            .resolve(tf.canonicalSpec())
            .resolve("$date.bin")

    fun hasDay(
        broker: String,
        symbol: String,
        tf: TimeWindow,
        date: LocalDate,
    ): Boolean = Files.exists(dayFile(broker, symbol, tf, date))

    fun writeDay(
        broker: String,
        symbol: String,
        tf: TimeWindow,
        date: LocalDate,
        bars: List<Candle>,
    ) {
        // Store the prefixed qktSymbol ("broker:symbol") so reads carry the key the engine routes by
        // (CandleHub is keyed by qktSymbol), matching how LocalMarketSource stamps bar rows.
        BinaryBarWriter().write(dayFile(broker, symbol, tf, date), "$broker:$symbol", tf.durationMs, bars)
    }

    fun readDay(
        broker: String,
        symbol: String,
        tf: TimeWindow,
        date: LocalDate,
    ): List<Candle> = BinaryBarFeed(dayFile(broker, symbol, tf, date)).candles()
}
