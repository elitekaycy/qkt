package com.qkt.backtest

import com.qkt.candles.TimeWindow

/**
 * Which replayed symbols fill triggered stops and limits at their own level rather than at the
 * triggering tick: exactly the symbols whose ticks are synthesized from bars, where the only intrabar
 * prints are the bar's extremes (see [com.qkt.broker.PaperBroker]). A symbol replayed from real ticks
 * fills at the tick, as live does. A continuous futures stream (`VENUE:ROOT@front`) synthesized from
 * bars covers the contracts it trades (`VENUE:ROOT_240927`), whose fills the venue sees by contract.
 *
 * e.g. a run on recorded XAUUSD ticks and fetched BTCUSD bars: `at("EXNESS:BTCUSD")` is true,
 * `at("EXNESS:XAUUSD")` false.
 *
 * Under tick-resolved fills no symbol is synthesized, but a bar the store has no ticks for is: while
 * its synthetic ticks replay, [noTickBars] makes `at` true for that symbol, so the bar fills as bar
 * fills would (#1439).
 *
 * [windows] holds the bar of every symbol a bar drives, synthesized or tick-resolved: its ticks carry made-up stamps
 * inside the bar, so [ReplaySessions] judges such a symbol's session by its bar instead.
 */
class BarFills(
    synthesized: Set<String>,
    /** The tick-resolved replay's bars without ticks; null on every other path. */
    val noTickBars: NoTickBars? = null,
    /** Each bar-driven symbol's bar, e.g. `EXNESS:EURUSD` to 4h; empty when every symbol replays recorded ticks. */
    val windows: Map<String, TimeWindow> = emptyMap(),
) {
    private val symbols = synthesized.filterNot { '@' in it }.toSet()
    private val continuousRoots = synthesized.filter { '@' in it }.map { it.substringBefore('@') }.toSet()
    private val contracts = HashMap<String, Boolean>()

    /** True when some symbol of the run is synthesized from bars. */
    val any: Boolean = synthesized.isNotEmpty()

    /** True when [symbol] (a replayed symbol, or a contract a synthesized stream trades) fills at the level. */
    fun at(symbol: String): Boolean {
        if (symbol in symbols) return true
        if (noTickBars != null && noTickBars.fillsAtLevel(symbol)) return true
        if (continuousRoots.isEmpty()) return false
        return contracts.getOrPut(symbol) { symbol.substringBeforeLast('_') in continuousRoots }
    }

    companion object {
        /** No symbol synthesized: every fill is at the triggering tick. */
        val NONE = BarFills(emptySet())
    }
}
