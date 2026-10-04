package com.qkt.instrument

import java.time.Instant

/**
 * Whether a backtest from `fromMs` to `toMs` has the funding rates of every perpetual it trades: stored
 * rates must start within three of the series' own intervals of the run's start, end within them of its
 * end, and never leave a longer gap between them (an hourly series may miss two rates, an 8-hourly one a
 * day's worth). A perpetual held without its rates would
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
            val maxGap = maxGap(rates)
            val inRun = rates.filter { it in fromMs - maxGap..toMs + maxGap }
            val gap =
                when {
                    inRun.isEmpty() -> "it has no stored funding rates over the run"
                    inRun.first() > fromMs + maxGap -> "its stored rates start ${day(inRun.first())}"
                    inRun.last() < toMs - maxGap -> "its stored rates end ${day(inRun.last())}"
                    else ->
                        inRun
                            .zipWithNext()
                            .firstOrNull { (a, b) ->
                                b - a > maxGap
                            }?.let { (a, b) -> "its stored rates skip ${day(a)} to ${day(b)}" }
                } ?: return@firstNotNullOfOrNull null
            "$symbol is a perpetual and $gap, so its funding would be missing: run " +
                "`qkt fetch $symbol --funding --from ${day(fromMs)} --to ${day(toMs)}`, or pass --funding off to " +
                "backtest without funding"
        }

    /** Three of the series' own intervals (its median spacing), so one missed rate passes and a run of them does not. */
    private fun maxGap(times: List<Long>): Long {
        val spacings = times.zipWithNext { a, b -> b - a }.sorted()
        return if (spacings.isEmpty()) DAY_MS else GAP_INTERVALS * spacings[(spacings.size - 1) / 2]
    }

    private fun day(ms: Long) = Instant.ofEpochMilli(ms).toString().substringBefore('T')

    private const val DAY_MS = 86_400_000L
    private const val GAP_INTERVALS = 3
}
