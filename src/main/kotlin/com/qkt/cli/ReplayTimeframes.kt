package com.qkt.cli

import com.qkt.candles.TimeWindow
import com.qkt.marketdata.store.BarCoverage
import com.qkt.marketdata.store.BinaryBarStore

/**
 * The bars a symbol without ticks replays when `--bars` is off. Each bar becomes four invented ticks, so the finest
 * stored timeframe that divides the strategy's own and covers the window gives fills closest to the real path: 1m
 * bars under a 15m strategy, not the 15m bars rolled up from them. Falls back to [declared] when none qualifies.
 */
internal fun finestReplayTimeframe(
    built: List<TimeWindow>,
    declared: TimeWindow,
    covers: (TimeWindow) -> Boolean,
): TimeWindow =
    built
        .filter { declared.durationMs % it.durationMs == 0L }
        .sortedBy { it.durationMs }
        .firstOrNull(covers) ?: declared

/** [finestReplayTimeframe] for each symbol of [declared], from what [store] holds; [coverage] is (broker, symbol, tf). */
internal fun finestReplayWindows(
    declared: Map<String, TimeWindow>,
    store: BinaryBarStore,
    coverage: (String, String, TimeWindow) -> BarCoverage,
): Map<String, TimeWindow> =
    declared.mapValues { (sym, tf) ->
        val (broker, bare) = BacktestBarReplay.brokerAndBare(sym)
        finestReplayTimeframe(
            store.builtTimeframes(broker, bare),
            tf,
        ) { coverage(broker, bare, it).missingDays.isEmpty() }
    }
