package com.qkt.marketdata.marks

import com.qkt.candles.TimeWindow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Whether a backtest from `fromMs` to `toMs` has the stored marks of every stream that reads them: each
 * UTC day of the run must be stored for the stream's symbol at the stream's window. A strategy reading
 * marks that were never fetched would see none and never act, so a gap is a refusal naming the fetch.
 */
object MarkCoverage {
    /** Why [reads] (symbol and window) cannot be served from [store] over the run, or null when they can. */
    fun problem(
        store: MarkStore,
        reads: Collection<Pair<String, Long>>,
        fromMs: Long,
        toMs: Long,
    ): String? =
        reads.distinct().firstNotNullOfOrNull { (symbol, windowMs) ->
            val tf = TimeWindow(windowMs).canonicalSpec()
            if ('@' in symbol) {
                return@firstNotNullOfOrNull "$symbol is a continuous futures stream: mark and index are read on a " +
                    "listed contract or a perpetual"
            }
            val first = day(fromMs)
            val last = day(toMs - 1)
            val missing =
                generateSequence(first) { it.plusDays(1) }
                    .takeWhile { !it.isAfter(last) }
                    .filterNot { store.has(symbol, windowMs, it) }
                    .toList()
            missing.firstOrNull()?.let {
                "$symbol reads mark/index every $tf but ${missing.size} day(s) of the run are not stored (from $it): " +
                    "run `qkt fetch $symbol --marks --tf $tf --from $first --to $last`"
            }
        }

    private fun day(ms: Long) = LocalDate.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC)
}
