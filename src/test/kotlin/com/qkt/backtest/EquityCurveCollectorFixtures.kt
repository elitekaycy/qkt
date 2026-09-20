package com.qkt.backtest

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.marketdata.Candle
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker

/**
 * A fresh bus, PnL calculators and clock per test, plus a flat candle builder, for the equity curve collector
 * tests.
 */
internal object EquityCurveCollectorFixtures {
    fun candle(
        close: String,
        endMs: Long,
        symbol: String = "X",
    ): Candle =
        Candle(
            symbol = symbol,
            open = Money.of(close),
            high = Money.of(close),
            low = Money.of(close),
            close = Money.of(close),
            volume = Money.of("1"),
            startTime = endMs - 60_000L,
            endTime = endMs,
        )

    data class Rig(
        val bus: EventBus,
        val pnl: PnLCalculator,
        val strategyPnL: StrategyPnL,
        val clock: FixedClock,
    )

    fun newRig(): Rig {
        val clock = FixedClock(0L)
        val sequencer = MonotonicSequenceGenerator()
        val bus = EventBus(clock, sequencer)
        val priceTracker = MarketPriceTracker()
        val strategyPositions = StrategyPositionTracker()
        val positions = strategyPositions.account
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPnL = StrategyPnL(strategyPositions, priceTracker)
        return Rig(bus, pnl, strategyPnL, clock)
    }
}
