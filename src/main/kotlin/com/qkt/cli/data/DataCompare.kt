package com.qkt.cli.data

import com.qkt.candles.TimeWindow
import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.InstrumentFiles
import com.qkt.common.SystemClock
import com.qkt.common.TimeRange
import com.qkt.marketdata.ingest.compare.SeriesCompare
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.store.DataRoot
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.LocalBarStore
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `qkt data compare <SYMBOL> --against <SYMBOL> --from <date> --to <date> [--tf 1m] [--json] [--data-root]`
 *
 * Compares two stored series of one instrument as a backtest reads them (bars from the bar store, or bars
 * built from ticks), typically an import against `qkt fetch` output for the same days: matched and missing
 * bars, the clock offset that best aligns them, and price differences in points. Exit 0 when the series are
 * identical or match within two points on the same clock, 1 when they differ.
 */
internal fun dataCompare(args: Args): Int {
    val first = args.positional(1)
    val second = args.option("against")
    val from = args.option("from")?.let(LocalDate::parse)
    val to = args.option("to")?.let(LocalDate::parse)
    if (first == null || second == null || from == null || to == null) {
        System.err.println(
            "qkt: usage: qkt data compare <SYMBOL> --against <SYMBOL> --from <date> --to <date> [--tf 1m]",
        )
        return ExitCodes.ARG_ERROR
    }
    val window = TimeWindow.parse(args.option("tf") ?: "1m")
    val root = DataRoot.forDataRoot(args.option("data-root"))
    val source = LocalMarketSource(DefaultDataStore(root), SystemClock(), LocalBarStore(root))
    val range =
        TimeRange(
            from.atStartOfDay(ZoneOffset.UTC).toInstant(),
            to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
        )
    val registry = InstrumentFiles.registry(root, args.option("instruments")?.let(Path::of))
    val point = registry.lookup(first)?.pointSize
    val result =
        SeriesCompare.compare(
            source.bars(first, window, range).toList(),
            source.bars(second, window, range).toList(),
            window.durationMs,
            point,
        )
    if (args.flag("json")) {
        println(
            buildJsonObject {
                put("first", first)
                put("second", second)
                put("verdict", result.verdict)
                put("matched", result.matched)
                put("onlyFirst", result.onlyFirst)
                put("onlySecond", result.onlySecond)
                put("offsetMs", result.offsetMs)
                put("summary", result.summary())
            },
        )
    } else {
        println("qkt data compare: $first vs $second ${window.canonicalSpec()} $from … $to")
        println("  ${result.summary()}")
    }
    return if (result.verdict == "identical" || result.verdict == "matches") ExitCodes.SUCCESS else ExitCodes.USER_ERROR
}
