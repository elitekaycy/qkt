package com.qkt.marketdata.depth

import java.time.Instant

/**
 * Whether a backtest from `fromMs` to `toMs` has the stored book of every contract a strategy reads depth of:
 * the snapshots must start within three of the series' own intervals (its median spacing) of the run's start,
 * end within them of its end, and never leave a longer gap. A strategy reading a book the run does not hold
 * would see none where live saw one, so a gap is a refusal, never a missing value.
 */
object BookDepthCoverage {
    /** Why [store] cannot serve the depth streams among [symbols] over the run, naming the fetch; null when it can. */
    fun problem(
        store: BookDepthStore,
        symbols: Collection<String>,
        fromMs: Long,
        toMs: Long,
    ): String? =
        symbols.mapNotNull(BookDepthSymbol::contract).distinct().firstNotNullOfOrNull { contract ->
            val times = store.times(contract, fromMs - DAY_MS, toMs + DAY_MS)
            val maxGap = maxGap(times)
            val inRun = times.filter { it in fromMs - maxGap..toMs + maxGap }
            val gap =
                when {
                    inRun.isEmpty() -> "no stored depth over the run"
                    inRun.first() > fromMs + maxGap -> "stored depth that starts ${at(inRun.first())}"
                    inRun.last() < toMs - maxGap -> "stored depth that ends ${at(inRun.last())}"
                    else ->
                        inRun
                            .zipWithNext()
                            .firstOrNull { (a, b) -> b - a > maxGap }
                            ?.let { (a, b) -> "stored depth that skips ${at(a)} to ${at(b)}" }
                } ?: return@firstNotNullOfOrNull null
            "the strategy reads the order-book depth of $contract and the run has $gap: run " +
                "`qkt fetch $contract --depth --from ${day(
                    fromMs,
                )} --to ${day(toMs)}` against a gateway that recorded it"
        }

    /** Three of the series' own intervals (its median spacing), so one missed snapshot passes and a run of them does not. */
    private fun maxGap(times: List<Long>): Long {
        val spacings = times.zipWithNext { a, b -> b - a }.sorted()
        return if (spacings.isEmpty()) DAY_MS else GAP_INTERVALS * spacings[(spacings.size - 1) / 2]
    }

    private fun day(ms: Long) = Instant.ofEpochMilli(ms).toString().substringBefore('T')

    private fun at(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private const val DAY_MS = 86_400_000L
    private const val GAP_INTERVALS = 3
}
