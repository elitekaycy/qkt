package com.qkt.backtest

import com.qkt.evidence.BarsWithoutTicks
import java.time.Instant

/**
 * The bars a tick-resolved `--bars` replay has no recorded ticks for, per symbol. Such a bar replays
 * its synthetic open, extremes and close, and its triggered stops and limits fill at their own level
 * (bar fills), exactly as `--no-tick-fills` fills that bar; never at the synthetic extreme (#1439).
 *
 * The replay feed writes it ([track], [Tally.atLevel]); the broker reads [fillsAtLevel] per fill.
 * [positionSign] orders each no-tick bar's extremes adverse-first for the open position, as the
 * bar feed does. One instance per replay: it counts, and logs each symbol's first such bar once.
 *
 * e.g. a EURUSD stop at 1.08478 inside a no-tick bar O=1.08635 L=1.077885 fills at 1.08478.
 */
class NoTickBars(
    internal val positionSign: (symbol: String) -> Int = { 0 },
) {
    /** One symbol's counts; [atLevel] is true while its last emitted tick is a no-tick bar's extreme or close. */
    class Tally internal constructor(
        val symbol: String,
    ) {
        internal var atLevel: Boolean = false
        internal var barsWithoutTicks: Int = 0
        internal var fillPossibleWithoutTicks: Int = 0
        internal var fillPossible: Int = 0

        /** Count one settled bar: [hasTicks] or not, and whether a fill could land in it. */
        internal fun bar(
            hasTicks: Boolean,
            fillPossible: Boolean,
            startMs: Long,
        ) {
            if (fillPossible) this.fillPossible += 1
            if (hasTicks) return
            barsWithoutTicks += 1
            if (!fillPossible) return
            fillPossibleWithoutTicks += 1
            if (fillPossibleWithoutTicks == 1) {
                log.info(
                    "{} has no ticks for a fill-possible bar at {}; such bars fill at their levels (bar fills)",
                    symbol,
                    Instant.ofEpochMilli(startMs),
                )
            }
        }
    }

    private val tallies = HashMap<String, Tally>()

    /** The tally the feed of [symbol] writes. */
    internal fun track(symbol: String): Tally = tallies.getOrPut(symbol) { Tally(symbol) }

    /** True when [symbol]'s current tick is a no-tick bar's extreme or close: a fill there is at its level. */
    fun fillsAtLevel(symbol: String): Boolean = tallies[symbol]?.atLevel == true

    /** Each symbol that had fill-possible bars without ticks, in symbol order. */
    fun counts(): List<BarsWithoutTicks> =
        tallies.values
            .filter { it.fillPossibleWithoutTicks > 0 }
            .sortedBy { it.symbol }
            .map { BarsWithoutTicks(it.symbol, it.fillPossibleWithoutTicks, it.fillPossible, it.barsWithoutTicks) }

    private companion object {
        val log = org.slf4j.LoggerFactory.getLogger(NoTickBars::class.java)
    }
}
