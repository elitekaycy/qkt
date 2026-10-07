package com.qkt.backtest

import com.qkt.backtest.metrics.DRAWDOWN_PERIOD_THRESHOLD
import com.qkt.backtest.metrics.DrawdownAnalyzer
import com.qkt.backtest.metrics.MonteCarlo
import com.qkt.backtest.metrics.calmar
import com.qkt.backtest.metrics.profitFactor
import com.qkt.backtest.metrics.sharpe
import com.qkt.backtest.metrics.sortino
import com.qkt.backtest.metrics.winLossStats
import com.qkt.common.Money
import com.qkt.risk.DrawdownTracker
import java.math.BigDecimal

object ReportBuilder {
    fun buildGlobal(
        trades: List<TradeRecord>,
        equityCurve: List<EquitySample>,
        finalRealized: BigDecimal,
        finalUnrealized: BigDecimal,
        annualizationFactor: BigDecimal,
        metrics: EquityMetrics? = null,
        commissionPaid: BigDecimal = BigDecimal.ZERO,
        swapPaid: BigDecimal = BigDecimal.ZERO,
        dailyAdjustments: Map<java.time.LocalDate, BigDecimal> = emptyMap(),
        tradedNotional: BigDecimal = BigDecimal.ZERO,
    ): PerformanceReport =
        build(
            trades,
            equityCurve,
            finalRealized,
            finalUnrealized,
            annualizationFactor,
            metrics,
            commissionPaid,
            swapPaid,
            dailyAdjustments,
            tradedNotional,
        )

    fun buildPerStrategy(
        strategyId: String,
        trades: List<TradeRecord>,
        equityCurve: List<EquitySample>,
        finalRealized: BigDecimal,
        finalUnrealized: BigDecimal,
        annualizationFactor: BigDecimal,
        metrics: EquityMetrics? = null,
        commissionPaid: BigDecimal = BigDecimal.ZERO,
        swapPaid: BigDecimal = BigDecimal.ZERO,
        dailyAdjustments: Map<java.time.LocalDate, BigDecimal> = emptyMap(),
        tradedNotional: BigDecimal = BigDecimal.ZERO,
    ): PerformanceReport {
        require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
        return build(
            trades,
            equityCurve,
            finalRealized,
            finalUnrealized,
            annualizationFactor,
            metrics,
            commissionPaid,
            swapPaid,
            dailyAdjustments,
            tradedNotional,
        )
    }

    /**
     * Build a report. Curve-derived metrics (drawdown, Sharpe, drawdown periods, starting equity)
     * come from [metrics] when supplied — the live path, where [equityCurve] is a thinned chart view
     * and recomputing from it would be wrong. When [metrics] is null they fall back to a one-pass
     * computation over [equityCurve] itself.
     */
    /**
     * One outcome per exit fill. A leg-routed entry fill carries only its own cost (commission), and the trade is
     * not complete until the leg exits, so that cost is folded into the leg's first exit outcome rather than
     * dropped: a round trip's win/loss, profit factor and Monte Carlo sample then include both fills' costs and
     * their sum equals the realized total. Fills without a leg id keep the exit-only outcome (nothing to match).
     */
    private fun tradeOutcomes(trades: List<TradeRecord>): List<BigDecimal> {
        val entryCost = HashMap<Pair<String, String>, BigDecimal>()
        val outcomes = ArrayList<BigDecimal>()
        for (t in trades) {
            val leg = t.legId
            if (!t.reducedExposure) {
                if (leg != null) entryCost.merge(t.strategyId to leg, t.realized, BigDecimal::add)
                continue
            }
            val carried = if (leg != null) entryCost.remove(t.strategyId to leg) else null
            outcomes.add(if (carried == null) t.realized else t.realized.add(carried))
        }
        return outcomes
    }

    private fun build(
        trades: List<TradeRecord>,
        equityCurve: List<EquitySample>,
        finalRealized: BigDecimal,
        finalUnrealized: BigDecimal,
        annualizationFactor: BigDecimal,
        metrics: EquityMetrics?,
        commissionPaid: BigDecimal,
        swapPaid: BigDecimal,
        dailyAdjustments: Map<java.time.LocalDate, BigDecimal>,
        tradedNotional: BigDecimal = BigDecimal.ZERO,
    ): PerformanceReport {
        val closingRealizeds = tradeOutcomes(trades)
        val outcomes = closingRealizeds.filter { it.signum() != 0 }
        val wins = outcomes.count { it.signum() > 0 }
        val winRate =
            if (outcomes.isEmpty()) {
                Money.ZERO
            } else {
                BigDecimal(wins)
                    .divide(BigDecimal(outcomes.size), Money.CONTEXT)
                    .setScale(Money.SCALE, Money.ROUNDING)
            }

        val pf = profitFactor(closingRealizeds)
        val wl = winLossStats(closingRealizeds)
        val drawdown = metrics?.maxDrawdown() ?: DrawdownTracker.fromCurve(equityCurve.map { it.equity })
        val sharpeR = metrics?.sharpe(annualizationFactor) ?: sharpe(equityCurve.map { it.equity }, annualizationFactor)
        val sortinoR =
            metrics?.sortino(annualizationFactor) ?: sortino(equityCurve.map { it.equity }, annualizationFactor)
        val drawdownPeriods =
            metrics?.drawdownPeriods() ?: DrawdownAnalyzer.analyze(equityCurve, DRAWDOWN_PERIOD_THRESHOLD)
        val startingEquity =
            metrics?.startingEquity() ?: equityCurve.firstOrNull()?.equity ?: BigDecimal.ZERO
        val turnover =
            if (startingEquity.signum() > 0) {
                tradedNotional.divide(startingEquity, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
            } else {
                Money.ZERO
            }
        // Calmar must be unitless: total return as a FRACTION of starting capital over the
        // drawdown fraction. Dollars over a fraction (the old shape) compares to nothing.
        // Null when there is no capital basis — an unanchored curve has no return fraction.
        val totalReturnFraction =
            if (startingEquity.signum() > 0) {
                finalRealized.add(finalUnrealized).divide(startingEquity, Money.CONTEXT)
            } else {
                null
            }
        val calmarR = totalReturnFraction?.let { calmar(it, drawdown) }
        val monteCarlo =
            if (closingRealizeds.size >= 30) {
                MonteCarlo.run(
                    tradeReturns = closingRealizeds,
                    startingEquity = startingEquity,
                    simulations = 1000,
                    seed = 42L,
                )
            } else {
                null
            }

        val dailyPnL =
            trades
                .groupBy {
                    java.time.Instant
                        .ofEpochMilli(it.trade.timestamp)
                        .atZone(java.time.ZoneOffset.UTC)
                        .toLocalDate()
                }.mapValues { (_, recs) ->
                    recs.fold(BigDecimal.ZERO) { acc, r -> acc.add(r.realized) }.setScale(Money.SCALE, Money.ROUNDING)
                }.toMutableMap()
        for ((date, adjustment) in dailyAdjustments) {
            dailyPnL[date] =
                (dailyPnL[date] ?: Money.ZERO)
                    .add(adjustment)
                    .setScale(Money.SCALE, Money.ROUNDING)
        }
        val maxDailyDd = metrics?.maxDailyDrawdown() ?: DailyDrawdownAccumulator.fromCurve(equityCurve)

        return PerformanceReport(
            realizedTotal = finalRealized.setScale(Money.SCALE, Money.ROUNDING),
            unrealizedTotal = finalUnrealized.setScale(Money.SCALE, Money.ROUNDING),
            totalPnL = finalRealized.add(finalUnrealized).setScale(Money.SCALE, Money.ROUNDING),
            tradeCount = trades.size,
            winRate = winRate,
            maxDrawdown = drawdown,
            profitFactor = pf,
            avgWin = wl.avgWin,
            avgLoss = wl.avgLoss,
            largestWin = wl.largestWin,
            largestLoss = wl.largestLoss,
            maxConsecutiveLosses = wl.maxConsecutiveLosses,
            sharpeRatio = sharpeR,
            calmarRatio = calmarR,
            equityCurve = equityCurve,
            drawdownPeriods = drawdownPeriods,
            monteCarlo = monteCarlo,
            commissionPaid = commissionPaid.setScale(Money.SCALE, Money.ROUNDING),
            swapPaid = swapPaid.setScale(Money.SCALE, Money.ROUNDING),
            dailyPnL = dailyPnL.toSortedMap(),
            maxDailyDrawdown = maxDailyDd,
            sortinoRatio = sortinoR,
            turnover = turnover,
            annualizationFactor = annualizationFactor,
        )
    }
}
