package com.qkt.strategy

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick

/**
 * Trading logic that turns market data into [Signal]s.
 *
 * Two callbacks: [onTick] runs on every market tick, [onCandle] runs only when a
 * configured aggregator emits a closed candle. Strategies must be deterministic given
 * their context (which carries an injected [com.qkt.common.Clock] and seeded random)
 * — that's what guarantees backtest=live parity.
 *
 * DSL-compiled strategies implement [com.qkt.dsl.compile.DslCompiledStrategy] and use
 * the hub-driven dispatch path; legacy hand-written strategies subscribe directly to
 * [com.qkt.events.TickEvent] / [com.qkt.events.CandleEvent].
 */
interface Strategy {
    /** Called for every published tick. Emit signals via [emit] — never block. */
    fun onTick(
        tick: Tick,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    )

    /** Called when a closed candle is published. Default no-op. */
    fun onCandle(
        candle: Candle,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {}
}
