package com.qkt.cli.data

import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.marketdata.Candle
import com.qkt.marketdata.openDayFeed
import com.qkt.marketdata.store.BinaryBarStore
import com.qkt.marketdata.store.DataRoot
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * `qkt data build-bars <symbol> --tf <interval> [--from] [--to] [--data-root <dir>]`
 *
 * Decodes the cached tick store for `<symbol>` ONCE, aggregates it to `<interval>` OHLC bars, and
 * writes a binary bar store (`bars/BACKTEST/<symbol>/<tf>/<day>.bin`). Backtests run with `--bars`
 * then replay these bars (no tick decode). Incremental: days already built are skipped.
 */
internal fun dataBuildBars(args: Args): Int {
    val symbol =
        args.positional(1) ?: run {
            System.err.println(
                "qkt: missing symbol. usage: qkt data build-bars <symbol> --tf <interval> [--from] [--to]",
            )
            return ExitCodes.ARG_ERROR
        }
    val tf =
        try {
            TimeWindow.parse(
                args.option("tf") ?: run {
                    System.err.println("qkt: missing --tf (e.g. --tf 15m)")
                    return ExitCodes.ARG_ERROR
                },
            )
        } catch (e: IllegalArgumentException) {
            System.err.println("qkt: error: ${e.message}")
            return ExitCodes.USER_ERROR
        }
    val root = DataRoot.forDataRoot(args.option("data-root"))
    val symDir = root.resolve("symbols").resolve(symbol)
    if (!Files.isDirectory(symDir)) {
        System.err.println("qkt: no cached tick data for '$symbol' at $symDir")
        return ExitCodes.USER_ERROR
    }
    val from = args.option("from")?.let { LocalDate.parse(it) }
    val to = args.option("to")?.let { LocalDate.parse(it) }
    val store = BinaryBarStore(root)
    val broker = "BACKTEST" // local tick data is referenced as BACKTEST:<symbol>

    val byDay = sortedMapOf<LocalDate, Path>()
    Files.list(symDir).use { stream ->
        stream.forEach { p ->
            val name = p.fileName.toString()
            val dayStr =
                when {
                    name.endsWith(".bin") -> name.removeSuffix(".bin")
                    name.endsWith(".csv.gz") -> name.removeSuffix(".csv.gz")
                    name.endsWith(".csv") -> name.removeSuffix(".csv")
                    else -> return@forEach
                }
            val day = runCatching { LocalDate.parse(dayStr) }.getOrNull() ?: return@forEach
            if (byDay[day] == null || name.endsWith(".bin")) byDay[day] = p // prefer .bin
        }
    }

    var built = 0
    var skipped = 0
    var totalBars = 0
    for ((day, path) in byDay) {
        if (from != null && day.isBefore(from)) continue
        if (to != null && !day.isBefore(to)) continue
        if (store.hasDay(broker, symbol, tf, day)) {
            skipped++
            continue
        }
        val candles = ArrayList<Candle>()
        val agg = CandleAggregator.standalone(tf) { candles.add(it) }
        openDayFeed(path).use { feed ->
            while (true) {
                val t = feed.next() ?: break
                agg.onTick(t)
            }
        }
        agg.flushClosed(Long.MAX_VALUE)
        store.writeDay(broker, symbol, tf, day, candles)
        built++
        totalBars += candles.size
    }
    println("qkt data build-bars: $symbol ${tf.canonicalSpec()} — built=$built skipped=$skipped bars=$totalBars")
    return ExitCodes.SUCCESS
}
