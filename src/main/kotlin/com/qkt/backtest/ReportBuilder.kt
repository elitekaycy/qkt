package com.qkt.backtest

import com.qkt.backtest.metrics.DRAWDOWN_PERIOD_THRESHOLD
import com.qkt.backtest.metrics.DrawdownAnalyzer
import com.qkt.backtest.metrics.MonteCarlo
import com.qkt.backtest.metrics.calmar
import com.qkt.backtest.metrics.profitFactor
import com.qkt.backtest.metrics.sharpe
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
    ): PerformanceReport =
        build(trades, equityCurve, finalRealized, finalUnrealized, annualizationFactor, metrics, commissionPaid)

    fun buildPerStrategy(
        strategyId: String,
        trades: List<TradeRecord>,
        equityCurve: List<EquitySample>,
        finalRealized: BigDecimal,
        finalUnrealized: BigDecimal,
        annualizationFactor: BigDecimal,
        metrics: EquityMetrics? = null,
        commissionPaid: BigDecimal = BigDecimal.ZERO,
    ): PerformanceReport {
        require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
        return build(trades, equityCurve, finalRealized, finalUnrealized, annualizationFactor, metrics, commissionPaid)
    }

    /**
     * Build a report. Curve-derived metrics (drawdown, Sharpe, drawdown periods, starting equity)
     * come from [metrics] when supplied — the live path, where [equityCurve] is a thinned chart view
     * and recomputing from it would be wrong. When [metrics] is null they fall back to a one-pass
     * computation over [equityCurve] itself.
     */
    private fun build(
        trades: List<TradeRecord>,
        equityCurve: List<EquitySample>,
        finalRealized: BigDecimal,
        finalUnrealized: BigDecimal,
        annualizationFactor: BigDecimal,
        metrics: EquityMetrics?,
        commissionPaid: BigDecimal,
    ): PerformanceReport {
        val realizeds = trades.map { it.realized }
        val closing = realizeds.filter { it.signum() != 0 }
        val wins = closing.count { it.signum() > 0 }
        val winRate =
            if (closing.isEmpty()) {
                Money.ZERO
            } else {
                BigDecimal(wins)
                    .divide(BigDecimal(closing.size), Money.CONTEXT)
                    .setScale(Money.SCALE, Money.ROUNDING)
            }

        val pf = profitFactor(realizeds)
        val wl = winLossStats(realizeds)
        val drawdown = metrics?.maxDrawdown() ?: DrawdownTracker.fromCurve(equityCurve.map { it.equity })
        val sharpeR = metrics?.sharpe(annualizationFactor) ?: sharpe(equityCurve.map { it.equity }, annualizationFactor)
        val drawdownPeriods =
            metrics?.drawdownPeriods() ?: DrawdownAnalyzer.analyze(equityCurve, DRAWDOWN_PERIOD_THRESHOLD)
        val startingEquity =
            metrics?.startingEquity() ?: equityCurve.firstOrNull()?.equity ?: BigDecimal.ZERO
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
            if (trades.size >= 30) {
                MonteCarlo.run(
                    tradeReturns = realizeds,
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
            dailyPnL = dailyPnL,
            maxDailyDrawdown = maxDailyDd,
        )
    }
}
