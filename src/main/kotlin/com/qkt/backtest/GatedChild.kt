package com.qkt.backtest

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal

/**
 * Wraps a portfolio child strategy in backtest so its behaviour matches a live
 * [com.qkt.cli.daemon.portfolio.PortfolioSupervisor]:
 *
 * - When the portfolio gate is active the inner strategy sees ticks and candles normally.
 * - When the gate transitions from active to inactive and [hold] is false, the wrapper emits
 *   market orders that flatten every traded symbol before the strategy is paused.
 * - When [hold] is true the strategy keeps running (so it can manage existing positions with
 *   exits) but the pipeline's [gateFor] still suppresses new risk-increasing entries.
 */
class GatedChild(
    private val strategyId: String,
    private val inner: Strategy,
    private val hold: Boolean,
    private val gateFor: (String) -> Boolean,
    private val flattenSymbols: List<String>,
) : Strategy {
    private var wasActive: Boolean = gateFor(strategyId)

    override fun onTick(
        tick: Tick,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        if (gateFor(strategyId) || hold) {
            inner.onTick(tick, ctx, emit)
        }
    }

    override fun onCandle(
        candle: Candle,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        val active = gateFor(strategyId)
        if (wasActive && !active && !hold) {
            flatten(ctx, emit)
        }
        wasActive = active
        if (active || hold) {
            inner.onCandle(candle, ctx, emit)
        }
    }

    private fun flatten(
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        for (symbol in flattenSymbols) {
            val qty = ctx.positions.positionFor(symbol)?.quantity ?: BigDecimal.ZERO
            when {
                qty.signum() > 0 -> emit(Signal.Sell(symbol, qty, force = true))
                qty.signum() < 0 -> emit(Signal.Buy(symbol, qty.abs(), force = true))
            }
        }
    }
}
