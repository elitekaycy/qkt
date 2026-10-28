package com.qkt.cli

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.TradingCalendar
import com.qkt.dsl.portfolio.PortfolioCompiled
import com.qkt.dsl.portfolio.PortfolioGate
import com.qkt.risk.book.Allocation
import com.qkt.risk.book.AllocationMethod
import com.qkt.risk.book.BookRiskConfig
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * What a portfolio backtest adds to a strategy backtest: the shared portfolio gate, so WHEN..RUN rules suppress child
 * signals exactly as PortfolioSupervisor does in live (the gate is fed closed candles before strategies evaluate them,
 * so its state is current for each bar), and the regime weights its book-risk allocation reads.
 */
internal class PortfolioBacktestWiring(
    compiled: PortfolioCompiled,
    from: Instant,
    calendar: TradingCalendar,
) {
    private val gate =
        PortfolioGate(ast = compiled.ast, clock = FixedClock(time = from.toEpochMilli()), calendar = calendar).also {
            it.prepare()
            it.initialState()
        }
    private val aliasToStrategyId = compiled.children.associate { it.alias to it.strategyId }

    /** Whether the child with this strategy id (`<portfolio>:<alias>`) may trade now. */
    val gateFor: (String) -> Boolean = { strategyId ->
        gate.currentState().activeByAlias[strategyId.substringAfter(":")] ==
            true
    }

    /** Feeds the gate a closed candle before the strategies evaluate it. */
    val preCandle: (com.qkt.marketdata.Candle) -> Unit = { candle -> gate.onCandle(candle) }

    /** The gate's current weight of each child, by strategy id. */
    val regimeWeights: () -> Map<String, BigDecimal> = {
        gate.currentState().weightByAlias.mapKeys { (alias, _) -> aliasToStrategyId[alias] ?: alias }
    }

    companion object {
        /**
         * The book-risk config a portfolio with `ALLOCATE` and `CAPITAL` runs under: [base] (config's, or the default)
         * with the declared capital and a regime-weighted allocation rebalanced every `REBALANCE EVERY` of [candleWindow]
         * bars, rounded up. Without both declarations the config's own book risk stands.
         */
        fun bookRisk(
            compiled: PortfolioCompiled,
            declaredCapital: BigDecimal?,
            base: BookRiskConfig?,
            candleWindow: TimeWindow?,
        ): BookRiskConfig? {
            val allocate = compiled.ast.allocate ?: return base
            declaredCapital ?: return base
            val config = base ?: BookRiskConfig()
            val rebalanceBars =
                allocate.rebalanceEveryDurationMs?.let { durationMs ->
                    candleWindow?.let { window ->
                        BigDecimal(durationMs).divide(BigDecimal(window.durationMs), 0, RoundingMode.CEILING).toInt()
                    } ?: 0
                } ?: 0
            return config.copy(
                capital = config.capital ?: declaredCapital,
                allocation = Allocation(method = AllocationMethod.REGIME_WEIGHTED, rebalanceEveryBars = rebalanceBars),
            )
        }
    }
}
