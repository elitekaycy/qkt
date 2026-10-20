package com.qkt.cli

import com.qkt.backtest.IncompleteTickMessage
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.store.BarCoverage
import java.time.LocalDate

/** What `--bars` says when [sym]'s [tf] bars do not cover the window: the missing days as ranges and the fix. */
internal fun missingBarsMessage(
    sym: String,
    tf: TimeWindow,
    coverage: BarCoverage,
    fromDay: LocalDate,
    toDay: LocalDate,
): String {
    val what = if (coverage.coveredTradingDays == 0) "no bars" else "incomplete bars"
    return "--bars: $what for $sym: ${coverage.coveredTradingDays}/${coverage.requestedTradingDays} trading days; " +
        "missing ${IncompleteTickMessage.collapseRanges(coverage.missingDays)}. " +
        missingBarsFix(sym, tf, fromDay, toDay)
}

/**
 * The step that supplies [sym]'s missing [tf] bars: built from its ticks for a local symbol (`EURUSD`,
 * `BACKTEST:EURUSD`), imported or fetched for a source's (`BINANCE:BTCUSDT`), whose bars have no ticks to build from.
 */
internal fun missingBarsFix(
    sym: String,
    tf: TimeWindow,
    fromDay: LocalDate,
    toDay: LocalDate,
): String {
    val (broker, bare) = BacktestBarReplay.brokerAndBare(sym)
    val range = "--from $fromDay --to ${toDay.plusDays(1)}"
    if (broker == "BACKTEST") return "Run: qkt data build-bars $bare --tf ${tf.canonicalSpec()} $range"
    val fetch = "qkt fetch $sym --tf ${tf.canonicalSpec()} $range"
    return "Import or fetch them: qkt data import <file> --as $sym, or $fetch"
}
