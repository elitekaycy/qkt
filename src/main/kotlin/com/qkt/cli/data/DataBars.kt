package com.qkt.cli.data

import com.qkt.candles.TimeWindow
import com.qkt.cli.Args
import com.qkt.cli.BacktestBarReplay
import com.qkt.cli.CliDataRoot
import com.qkt.cli.ExitCodes
import com.qkt.common.SystemClock
import com.qkt.common.TimeRange
import com.qkt.marketdata.rollUpToGrid
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.store.BinaryBarStore
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.LocalBarStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `qkt data bars <SYMBOL|SOURCE:SYMBOL> --tf <tf> --from <date> --to <date> [--bars] [--data-root]`
 *
 * Prints a series' bars exactly as a backtest reads them, as CSV: stored bars at that timeframe, bars rolled up
 * from a finer stored timeframe, or bars built from ticks, through the same source a backtest uses (`--bars` reads
 * the built `.bin` tier as `qkt backtest --bars` does). `--to` is inclusive, as in `qkt data compare`. Exit 1 when
 * the range holds no bars.
 */
internal fun dataBars(args: Args): Int {
    val symbol = args.positional(1)
    val from = args.option("from")?.let(LocalDate::parse)
    val to = args.option("to")?.let(LocalDate::parse)
    val tf = args.option("tf")
    if (symbol == null || from == null || to == null || tf == null) {
        System.err.println(
            "qkt: usage: qkt data bars <SYMBOL|SOURCE:SYMBOL> --tf <tf> --from <date> --to <date> [--bars]",
        )
        return ExitCodes.ARG_ERROR
    }
    val window =
        try {
            TimeWindow.parse(tf)
        } catch (e: IllegalArgumentException) {
            System.err.println("qkt: error: ${e.message}")
            return ExitCodes.USER_ERROR
        }
    val root = CliDataRoot.of(args)
    val source =
        LocalMarketSource(
            DefaultDataStore(root),
            SystemClock(),
            LocalBarStore(root),
            if (args.flag("bars")) BinaryBarStore(root) else null,
        )
    val range =
        TimeRange(
            from.atStartOfDay(ZoneOffset.UTC).toInstant(),
            to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
        )
    // `--bars` replays the coarsest built timeframe that divides the one asked for, as qkt backtest --bars does.
    val bars =
        if (args.flag("bars")) {
            val (broker, bare) = BacktestBarReplay.brokerAndBare(symbol)
            val built =
                BinaryBarStore(root)
                    .builtTimeframes(broker, bare)
                    .filter { window.durationMs % it.durationMs == 0L }
                    .maxByOrNull { it.durationMs }
            if (built == null || built == window) {
                source.bars(symbol, window, range)
            } else {
                System.err.println("qkt data bars: rolled up from built ${built.canonicalSpec()} bars")
                rollUpToGrid(source.bars(symbol, built, range), window, range.to.toEpochMilli())
            }
        } else {
            source.bars(symbol, window, range)
        }
    var count = 0
    println("time,timestamp,open,high,low,close,volume")
    for (bar in bars) {
        println(
            listOf(
                Instant.ofEpochMilli(bar.startTime).toString(),
                bar.startTime.toString(),
                bar.open.toPlainString(),
                bar.high.toPlainString(),
                bar.low.toPlainString(),
                bar.close.toPlainString(),
                bar.volume.toPlainString(),
            ).joinToString(","),
        )
        count++
    }
    if (count == 0) {
        System.err.println("qkt: no ${window.canonicalSpec()} bars for $symbol between $from and $to")
        return ExitCodes.USER_ERROR
    }
    System.err.println("qkt data bars: $symbol ${window.canonicalSpec()} $from … $to — $count bars")
    return ExitCodes.SUCCESS
}
