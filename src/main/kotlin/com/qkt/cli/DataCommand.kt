package com.qkt.cli

import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.Candle
import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.Tick
import com.qkt.marketdata.openDayFeed
import com.qkt.marketdata.store.BinaryBarStore
import com.qkt.marketdata.store.DataRoot
import com.qkt.marketdata.store.DayFileIntegrity
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * `qkt data verify <symbol> [--data-root <dir>]`
 *
 * Reads every cached day file for a tick-store symbol and reports its quality — tick count and
 * largest intra-day gap — flagging empty, corrupt, or gappy days. The backtester keys coverage on
 * file *presence*, so a truncated or sparse day is otherwise concatenated in silently; this surfaces
 * it for any stream (not just Dukascopy). Exits non-zero when any day is flagged.
 */
class DataCommand(
    private val args: Args,
) {
    fun run(): Int =
        when (val action = args.positional(0)) {
            "verify" -> verify()
            "convert" -> convert()
            "build-bars" -> buildBars()
            else -> {
                System.err.println("qkt: unknown data action '${action ?: ""}' (expected: verify, convert, build-bars)")
                System.err.println("usage: qkt data <verify|convert|build-bars> <symbol> [--data-root <dir>]")
                ExitCodes.ARG_ERROR
            }
        }

    private fun verify(): Int {
        val symbol =
            args.positional(1) ?: run {
                System.err.println("qkt: missing symbol. usage: qkt data verify <symbol> [--data-root <dir>]")
                return ExitCodes.ARG_ERROR
            }
        val root = DataRoot.forDataRoot(args.option("data-root"))
        val symDir = root.resolve("symbols").resolve(symbol)
        if (!Files.isDirectory(symDir)) {
            System.err.println("qkt: no cached tick data for '$symbol' at $symDir")
            return ExitCodes.USER_ERROR
        }
        val dayFiles =
            Files.list(symDir).use { stream ->
                stream
                    .filter { it.fileName.toString().endsWith(".csv") || it.fileName.toString().endsWith(".csv.gz") }
                    .sorted()
                    .toList()
            }
        if (dayFiles.isEmpty()) {
            System.err.println("qkt: no day files for '$symbol' under $symDir")
            return ExitCodes.USER_ERROR
        }

        println("qkt data verify: $symbol (${dayFiles.size} day files) under $symDir")
        var flagged = 0
        var totalTicks = 0L
        for (path in dayFiles) {
            val day =
                path.fileName
                    .toString()
                    .removeSuffix(".gz")
                    .removeSuffix(".csv")
            val q = DayFileIntegrity.inspect(path)
            totalTicks += q.tickCount
            val status =
                when {
                    !q.readable -> "CORRUPT (unreadable)"
                    q.isEmpty -> "EMPTY (0 ticks)"
                    q.maxGapMs >= SUSPICIOUS_GAP_MS -> "GAP ${q.maxGapMs / 3_600_000}h"
                    else -> "ok"
                }
            if (status != "ok") flagged++
            println("  $day  ticks=${q.tickCount}  maxGap=${q.maxGapMs / 60_000}m  $status")
        }
        println("qkt data verify: done — ${dayFiles.size} days, $totalTicks ticks, $flagged flagged")
        return if (flagged > 0) ExitCodes.USER_ERROR else ExitCodes.SUCCESS
    }

    /**
     * `qkt data convert <symbol> [--from <date>] [--to <date>] [--prune] [--data-root <dir>]`
     *
     * Migrates a symbol's cached `.csv.gz` day files to the binary tick format (`.bin`), which the
     * data store then prefers for far faster decode. Idempotent — skips days already converted.
     * Leaves the CSV in place unless `--prune` is given. Optional `--from` (inclusive) / `--to`
     * (exclusive) bound the days converted.
     */
    private fun convert(): Int {
        val symbol =
            args.positional(1) ?: run {
                System.err.println(
                    "qkt: missing symbol. usage: qkt data convert <symbol> [--from] [--to] [--prune]",
                )
                return ExitCodes.ARG_ERROR
            }
        val root = DataRoot.forDataRoot(args.option("data-root"))
        val symDir = root.resolve("symbols").resolve(symbol)
        if (!Files.isDirectory(symDir)) {
            System.err.println("qkt: no cached tick data for '$symbol' at $symDir")
            return ExitCodes.USER_ERROR
        }
        val from = args.option("from")?.let { LocalDate.parse(it) }
        val to = args.option("to")?.let { LocalDate.parse(it) }
        val prune = args.flag("prune")

        val csvDays =
            Files.list(symDir).use { stream ->
                stream
                    .filter { it.fileName.toString().endsWith(".csv.gz") }
                    .sorted()
                    .toList()
            }
        val writer = BinaryTickWriter()
        var converted = 0
        var skipped = 0
        for (csv in csvDays) {
            val day = LocalDate.parse(csv.fileName.toString().removeSuffix(".csv.gz"))
            if (from != null && day.isBefore(from)) continue
            if (to != null && !day.isBefore(to)) continue
            val bin = symDir.resolve("$day.bin")
            if (Files.exists(bin)) {
                skipped++
                continue
            }
            val ticks = mutableListOf<Tick>()
            CsvTickFeed(csv).use { feed ->
                while (true) {
                    val t = feed.next() ?: break
                    ticks.add(t)
                }
            }
            writer.write(bin, symbol, ticks)
            if (prune) Files.deleteIfExists(csv)
            converted++
        }
        println("qkt data convert: $symbol — converted=$converted skipped=$skipped prune=$prune")
        return ExitCodes.SUCCESS
    }

    /**
     * `qkt data build-bars <symbol> --tf <interval> [--from] [--to] [--data-root <dir>]`
     *
     * Decodes the cached tick store for `<symbol>` ONCE, aggregates it to `<interval>` OHLC bars, and
     * writes a binary bar store (`bars/BACKTEST/<symbol>/<tf>/<day>.bin`). Backtests run with `--bars`
     * then replay these bars (no tick decode). Incremental: days already built are skipped.
     */
    private fun buildBars(): Int {
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

    private companion object {
        /** Intra-day gap (ms) above which a day file is flagged as possibly partial. 6h. */
        private const val SUSPICIOUS_GAP_MS = 6 * 60 * 60 * 1000L
    }
}
