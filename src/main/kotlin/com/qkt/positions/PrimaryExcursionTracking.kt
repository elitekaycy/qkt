package com.qkt.positions

import com.qkt.marketdata.Candle
import java.math.BigDecimal

/**
 * Max-favorable and max-adverse excursion of each strategy's PRIMARY leg per symbol, driven by
 * market ticks and read by the DSL `POSITION.<stream>.mfe` / `.mae` accessors.
 * [StrategyPositionTracker] exposes it by delegating to the excursion trackers it owns.
 */
interface PrimaryExcursionTracking {
    /**
     * Drive the per-PRIMARY MFE trackers with a market tick. Called by the runtime on
     * every [com.qkt.events.TickEvent]; cheap when there are no positions on the symbol.
     */
    fun onTick(
        symbol: String,
        price: BigDecimal,
    )

    /**
     * Extend the tracked excursion on [symbol] with bars printed while the daemon was down.
     * Only bars that started after the tracked leg opened count; the bar spanning the entry is
     * skipped because it also holds pre-entry prices. Bars are mid-based, like [onTick].
     */
    fun extendExcursion(
        strategyId: String,
        symbol: String,
        candles: List<Candle>,
    )

    /**
     * Current MFE of the PRIMARY leg on [symbol] for [strategyId], or null if no primary
     * exists. Backs the DSL accessor `POSITION.<stream>.mfe`.
     */
    fun primaryMfeFor(
        strategyId: String,
        symbol: String,
    ): BigDecimal?

    /**
     * Current MAE of the PRIMARY leg on [symbol] for [strategyId], or null if no primary
     * exists. Backs the DSL accessor `POSITION.<stream>.mae`.
     */
    fun primaryMaeFor(
        strategyId: String,
        symbol: String,
    ): BigDecimal?
}
