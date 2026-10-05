package com.qkt.marketdata.openinterest

import java.time.Instant

/**
 * Whether a backtest from `fromMs` to `toMs` has the stored open interest of every contract a strategy reads
 * it of: the figures must start within three of the series' own intervals (its median spacing) of the run's
 * start, end within them of its end, and never leave a longer gap. A strategy reading open interest the run
 * does not hold would see none where live saw one, so a gap is a refusal, never a missing value.
 */
object OpenInterestCoverage {
    /** Why [store] cannot serve the open-interest streams among [symbols] over the run, naming the fetch; null when it can. */
    fun problem(
        store: OpenInterestStore,
        symbols: Collection<String>,
        fromMs: Long,
        toMs: Long,
    ): String? =
        symbols.distinct().firstNotNullOfOrNull { symbol ->
            val contract = OpenInterestSymbol.contract(symbol) ?: return@firstNotNullOfOrNull null
            val times = store.read(contract).orEmpty().map { it.timeMs }
            val maxGap = maxGap(times)
            val inRun = times.filter { it in fromMs - maxGap..toMs + maxGap }
            val gap =
                when {
                    inRun.isEmpty() -> "no stored open interest over the run"
                    inRun.first() > fromMs + maxGap -> "stored open interest that starts ${day(inRun.first())}"
                    inRun.last() < toMs - maxGap -> "stored open interest that ends ${day(inRun.last())}"
                    else ->
                        inRun
                            .zipWithNext()
                            .firstOrNull { (a, b) -> b - a > maxGap }
                            ?.let { (a, b) -> "stored open interest that skips ${day(a)} to ${day(b)}" }
                } ?: return@firstNotNullOfOrNull null
            "the strategy reads the open interest of $contract and the run has $gap: run " +
                "`qkt fetch $contract --open-interest --from ${day(fromMs)} --to ${day(toMs)}`"
        }

    /** Three of the series' own intervals (its median spacing), so one missed figure passes and a run of them does not. */
    private fun maxGap(times: List<Long>): Long {
        val spacings = times.zipWithNext { a, b -> b - a }.sorted()
        return if (spacings.isEmpty()) DAY_MS else GAP_INTERVALS * spacings[(spacings.size - 1) / 2]
    }

    private fun day(ms: Long) = Instant.ofEpochMilli(ms).toString().substringBefore('T')

    private const val DAY_MS = 86_400_000L
    private const val GAP_INTERVALS = 3
}
