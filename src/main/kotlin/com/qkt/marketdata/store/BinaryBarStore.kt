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
 * A day is stored when its file holds at least one bar (no separate manifest — bars are cheap to rebuild): a
 * header-only file, written for a day whose ticks had not arrived yet, is not coverage. Written by
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

    /** Whether the day's file holds at least one bar. */
    fun hasDay(
        broker: String,
        symbol: String,
        tf: TimeWindow,
        date: LocalDate,
    ): Boolean = (barCount(broker, symbol, tf, date) ?: 0) > 0

    /** The number of bars the day's file holds, read from its header alone; null when there is no file. */
    fun barCount(
        broker: String,
        symbol: String,
        tf: TimeWindow,
        date: LocalDate,
    ): Int? = BinaryBarDayFile.barCount(dayFile(broker, symbol, tf, date))

    /** The start times of the day's bars, decoding nothing else; null when there is no file. */
    fun startTimes(
        broker: String,
        symbol: String,
        tf: TimeWindow,
        date: LocalDate,
    ): LongArray? = BinaryBarDayFile.startTimes(dayFile(broker, symbol, tf, date))

    /**
     * Whether the day's bars exist and were built no earlier than [source] last changed. A day built with no bars
     * from a tick file that now holds ticks is not: e.g. a day built while its fetch was still empty.
     */
    fun isBuiltFrom(
        broker: String,
        symbol: String,
        tf: TimeWindow,
        date: LocalDate,
        source: Path,
    ): Boolean {
        val built = dayFile(broker, symbol, tf, date)
        val count = barCount(broker, symbol, tf, date) ?: return false
        if (Files.getLastModifiedTime(built) < Files.getLastModifiedTime(source)) return false
        return count > 0 || !BinaryBarDayFile.holdsTicks(source)
    }

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

    /**
     * Timeframes that have a bar directory for this symbol, e.g. with `bars/BACKTEST/EURUSD/1m`
     * and `.../30m` present this returns `[1m, 30m]` (in unspecified order). Empty if the symbol
     * has no bars. Lets the `--bars` resolver pick the coarsest built tf that divides a strategy's
     * declared tf, so a 30m strategy can replay off 1m bars without pre-building every tf.
     */
    fun builtTimeframes(
        broker: String,
        symbol: String,
    ): List<TimeWindow> {
        // A derived series such as `IMBALANCE:BINANCE_UM:BTCUSDT` splits into a bare part that still
        // holds ':', which is no file name on Windows; such a series never has bars of its own.
        val dir =
            try {
                root.resolve("bars").resolve(broker).resolve(symbol)
            } catch (_: java.nio.file.InvalidPathException) {
                return emptyList()
            }
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.newDirectoryStream(dir).use { entries ->
            entries
                .filter { Files.isDirectory(it) }
                .mapNotNull { runCatching { TimeWindow.parse(it.fileName.toString()) }.getOrNull() }
        }
    }
}
