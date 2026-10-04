package com.qkt.instrument

import java.time.Instant

/**
 * Whether a backtest from `fromMs` to `toMs` has the funding rates of every perpetual it trades: stored
 * rates must start within [MAX_GAP_MS] of the run's start, end within it of its end, and never leave a
 * longer gap between them (no venue funds less often than daily). A perpetual held without its rates would
 * silently drift from the venue by its funding, so a gap is a refusal, never a zero.
 */
object FundingCoverage {
    /** Why [registry]'s rates cannot fund [symbols] over the run, naming the fetch that would; null when they can. */
    fun problem(
        registry: InstrumentRegistry,
        symbols: Collection<String>,
        fromMs: Long,
        toMs: Long,
    ): String? =
        symbols.distinct().firstNotNullOfOrNull { symbol ->
            if ((registry.lookup(symbol)?.derivative as? FutureTerms)?.perpetual !=
                true
            ) {
                return@firstNotNullOfOrNull null
            }
            val rates =
                registry
                    .futures()
                    ?.fundingRates(symbol)
                    .orEmpty()
                    .map { it.timeMs }
            val inRun = rates.filter { it in fromMs - MAX_GAP_MS..toMs + MAX_GAP_MS }
            val gap =
                when {
                    inRun.isEmpty() -> "it has no stored funding rates over the run"
                    inRun.first() > fromMs + MAX_GAP_MS -> "its stored rates start ${day(inRun.first())}"
                    inRun.last() < toMs - MAX_GAP_MS -> "its stored rates end ${day(inRun.last())}"
                    else ->
                        inRun
                            .zipWithNext()
                            .firstOrNull { (a, b) ->
                                b - a > MAX_GAP_MS
                            }?.let { (a, b) -> "its stored rates skip ${day(a)} to ${day(b)}" }
                } ?: return@firstNotNullOfOrNull null
            "$symbol is a perpetual and $gap, so its funding would be missing: run " +
                "`qkt fetch $symbol --funding --from ${day(fromMs)} --to ${day(toMs)}`, or pass --funding off to " +
                "backtest without funding"
        }

    private fun day(ms: Long) = Instant.ofEpochMilli(ms).toString().substringBefore('T')

    private const val MAX_GAP_MS = 86_400_000L
}
