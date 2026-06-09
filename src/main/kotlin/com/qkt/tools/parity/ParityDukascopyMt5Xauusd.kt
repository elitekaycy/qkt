package com.qkt.tools.parity

import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Candle
import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.live.mt5.Mt5DataClient
import com.qkt.marketdata.store.dukascopy.DukascopyTickFetcher
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Compares the *actual backtest data source* (dukascopy ticks) against the *live broker feed* (MT5
 * historical bars) for one UTC day of XAUUSD.
 *
 * This is the data-source companion to `runParityBarsXauusd` (which compared TradingView vs MT5).
 * Since v0.35.0 the backtest auto-fetches from dukascopy, so dukascopy — not TradingView — is the
 * source a backtest actually replays. This tool answers: "are the prices my backtest sees the same
 * prices my broker recorded?"
 *
 * How it works:
 *  1. Fetch one dukascopy day with the real [DukascopyTickFetcher] — the same code a backtest uses.
 *  2. Aggregate those ticks to bars with the engine's own [CandleAggregator] — so the bars are
 *     exactly what a backtest strategy on that timeframe would see (close = mid of the last tick).
 *  3. Fetch the broker's own bars for the same window from the MT5 gateway.
 *  4. Align by bar-start and report close-price deltas, plus a spread comparison (the cost a
 *     paper-broker backtest ignores).
 *
 * Env vars (all optional):
 *   DUKA_SYMBOL=XAUUSD        bare dukascopy symbol (the backtest source)
 *   MT5_SYMBOL=XAUUSDm        broker symbol on the gateway
 *   DAY=2026-06-04            UTC day to compare
 *   TIMEFRAME=1m              bar size for the comparison
 *   MT5_URL=http://localhost:5003
 *   OUT=docs/parity/parity-dukascopy-vs-mt5-xauusd.md
 *
 * Prerequisite: SSH tunnel to the MT5 gateway:
 *   ssh -L 5003:localhost:5003 root@173.249.58.247
 */
fun main() {
    val dukaSymbol = System.getenv("DUKA_SYMBOL") ?: "XAUUSD"
    val mt5Symbol = System.getenv("MT5_SYMBOL") ?: "XAUUSDm"
    val day = LocalDate.parse(System.getenv("DAY") ?: "2026-06-04")
    val tfSpec = System.getenv("TIMEFRAME") ?: "1m"
    val mt5Url = System.getenv("MT5_URL") ?: "http://localhost:5003"
    val outPath = System.getenv("OUT") ?: "docs/parity/parity-dukascopy-vs-mt5-xauusd.md"

    val window = TimeWindow.parse(tfSpec)
    println("[parity-data] DUKA=$dukaSymbol  MT5=$mt5Symbol @ $mt5Url  day=$day  tf=$tfSpec")

    // 1 + 2: fetch dukascopy and aggregate with the engine's aggregator.
    val tmp = Files.createTempDirectory("parity-duka")
    val csvGz = tmp.resolve("$dukaSymbol.csv.gz")
    DukascopyTickFetcher().fetch(symbol = dukaSymbol, day = day, target = csvGz)

    val dukaBars = mutableListOf<Candle>()
    val spreads = mutableListOf<BigDecimal>()
    val aggregator = CandleAggregator.standalone(window) { c -> dukaBars.add(c) }
    CsvTickFeed(csvGz).use { feed ->
        while (true) {
            val tick = feed.next() ?: break
            aggregator.onTick(tick)
            val b = tick.bid
            val a = tick.ask
            if (b != null && a != null) spreads.add(a.subtract(b))
        }
    }
    println("[parity-data] dukascopy: ${spreads.size} ticks -> ${dukaBars.size} $tfSpec bars")

    // 3: broker bars for the same UTC day. The fetch window is padded to absorb any broker-clock
    // skew, then trimmed to exactly the dukascopy day so the coverage numbers compare like for like.
    val mt5Tf = windowToMt5(window)
    val dayStartMs = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val dayEndMs =
        day
            .plusDays(1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    val from = day.minusDays(1).atTime(20, 0).toInstant(ZoneOffset.UTC)
    val to = day.plusDays(1).atTime(4, 0).toInstant(ZoneOffset.UTC)
    val mt5Bars =
        Mt5DataClient(mt5Url)
            .fetchBarsByRange(mt5Symbol, mt5Tf, from.toString().removeSuffix("Z"), to.toString().removeSuffix("Z"))
            .filter { it.startTime in dayStartMs until dayEndMs }
    println("[parity-data] mt5: ${mt5Bars.size} $mt5Tf bars")

    // 4: align + compare (reuse the proven bars-parity math; relabel in the report below).
    val stats = BarsParity.compare(tv = dukaBars, mt5 = mt5Bars)
    val md =
        renderMarkdown(
            dukaSymbol = dukaSymbol,
            mt5Symbol = mt5Symbol,
            day = day,
            tfSpec = tfSpec,
            stats = stats,
            dukaSpreads = spreads,
            generatedAt = Instant.now(),
        )

    val out = Paths.get(outPath)
    out.parent?.let { Files.createDirectories(it) }
    Files.writeString(out, md)
    println(
        "[parity-data] wrote $outPath  (aligned=${stats.alignedCount}, " +
            "mean |Δclose|=${stats.meanCloseDelta.toPlainString()})",
    )
}

private fun windowToMt5(window: TimeWindow): String =
    when (window.durationMs) {
        60_000L -> "M1"
        300_000L -> "M5"
        900_000L -> "M15"
        3_600_000L -> "H1"
        14_400_000L -> "H4"
        86_400_000L -> "D1"
        else -> error("unsupported timeframe for MT5: ${window.durationMs}ms")
    }

private val MC = MathContext(12, RoundingMode.HALF_UP)

private fun quantile(
    sorted: List<BigDecimal>,
    pct: Int,
): BigDecimal {
    if (sorted.isEmpty()) return BigDecimal.ZERO
    val idx = (sorted.size * pct / 100).coerceAtMost(sorted.size - 1)
    return sorted[idx]
}

private fun renderMarkdown(
    dukaSymbol: String,
    mt5Symbol: String,
    day: LocalDate,
    tfSpec: String,
    stats: ParityStats,
    dukaSpreads: List<BigDecimal>,
    generatedAt: Instant,
): String {
    val sortedSpreads = dukaSpreads.sorted()
    val meanSpread =
        if (dukaSpreads.isEmpty()) {
            BigDecimal.ZERO
        } else {
            dukaSpreads
                .fold(BigDecimal.ZERO) { acc, s -> acc + s }
                .divide(BigDecimal(dukaSpreads.size), MC)
                .setScale(5, RoundingMode.HALF_UP)
        }
    return buildString {
        appendLine("# Data-source parity — XAUUSD — dukascopy (backtest) vs MT5 (live)")
        appendLine()
        appendLine("Generated: $generatedAt")
        appendLine("Day: $day  Timeframe: $tfSpec")
        appendLine("Source A (backtest): dukascopy `$dukaSymbol` ticks, aggregated by the engine's CandleAggregator")
        appendLine("Source B (live): MT5 broker `$mt5Symbol` historical bars via the gateway")
        appendLine()
        appendLine("## Coverage")
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("| --- | --- |")
        appendLine("| Dukascopy bars | ${stats.tvCount} |")
        appendLine("| MT5 bars | ${stats.mt5Count} |")
        appendLine("| Aligned | ${stats.alignedCount} |")
        appendLine("| Only in dukascopy | ${stats.onlyInTv.size} |")
        appendLine("| Only in MT5 | ${stats.onlyInMt5.size} |")
        appendLine()
        appendLine("## Close-price parity (the prices your strategy decides on)")
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("| --- | --- |")
        appendLine("| Mean abs(close_duka − close_mt5) | ${stats.meanCloseDelta.toPlainString()} |")
        appendLine("| p95 abs(close_duka − close_mt5) | ${stats.p95CloseDelta.toPlainString()} |")
        appendLine("| Max abs(close_duka − close_mt5) | ${stats.maxCloseDelta.toPlainString()} |")
        appendLine("| Mean relative (bps) | ${stats.meanRelativeCloseBps.toPlainString()} |")
        stats.maxCloseDeltaAt?.let { appendLine("| Max delta at | ${Instant.ofEpochMilli(it)} |") }
        appendLine()
        appendLine("## Spread — the cost a paper backtest ignores")
        appendLine()
        appendLine("Dukascopy ticks carry a real bid/ask; a paper-broker backtest throws it away and")
        appendLine("fills at the mid. These are the spreads present in the data but not in paper PnL.")
        appendLine("Compare against the broker spread the MT5 gateway reports for $mt5Symbol.")
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("| --- | --- |")
        appendLine("| Dukascopy mean spread | ${meanSpread.toPlainString()} |")
        appendLine("| Dukascopy p50 spread | ${quantile(sortedSpreads, 50).toPlainString()} |")
        appendLine("| Dukascopy p95 spread | ${quantile(sortedSpreads, 95).toPlainString()} |")
        appendLine("| Dukascopy max spread | ${(sortedSpreads.lastOrNull() ?: BigDecimal.ZERO).toPlainString()} |")
        appendLine()
        appendLine("## How to read this")
        appendLine()
        appendLine("- **Close parity** measures whether the *price level* your backtest replays matches what")
        appendLine("  the broker recorded. Small deltas (a fraction of a dollar / a few bps on gold) mean the")
        appendLine("  data source is faithful: a backtest that fires on a price will see that price live too.")
        appendLine("- **It does not measure execution.** Even with identical prices, live fills pay the spread")
        appendLine("  and slippage; the default paper backtest does not. That gap is catalogued separately in")
        appendLine("  [backtest-vs-live.md](backtest-vs-live.md). Use `--broker mt5-sim` to model the spread.")
        appendLine("- **Unaligned bars** are usually session edges (the broker's day starts/ends at a different")
        appendLine("  wall-clock than 00:00 UTC) — expected, not a data fault, as long as the interior aligns.")
        appendLine()
        appendLine("## Per-bar (last 20 aligned)")
        appendLine()
        appendLine("| time | duka close | mt5 close | abs Δclose | abs Δopen | abs Δhigh | abs Δlow |")
        appendLine("| --- | --- | --- | --- | --- | --- | --- |")
        for (b in stats.aligned.takeLast(20)) {
            appendLine(
                "| ${Instant.ofEpochMilli(b.startTime)} | ${b.tv.close.toPlainString()} | " +
                    "${b.mt5.close.toPlainString()} | ${b.closeDelta.toPlainString()} | " +
                    "${b.openDelta.toPlainString()} | ${b.highDelta.toPlainString()} | " +
                    "${b.lowDelta.toPlainString()} |",
            )
        }
    }
}
