package com.qkt.backtest

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
    ): PerformanceReport = build(trades, equityCurve, finalRealized, finalUnrealized, annualizationFactor)

    fun buildPerStrategy(
        strategyId: String,
        trades: List<TradeRecord>,
        equityCurve: List<EquitySample>,
        finalRealized: BigDecimal,
        finalUnrealized: BigDecimal,
        annualizationFactor: BigDecimal,
    ): PerformanceReport {
        require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
        return build(trades, equityCurve, finalRealized, finalUnrealized, annualizationFactor)
    }

    private fun build(
        trades: List<TradeRecord>,
        equityCurve: List<EquitySample>,
        finalRealized: BigDecimal,
        finalUnrealized: BigDecimal,
        annualizationFactor: BigDecimal,
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
        val drawdown = DrawdownTracker.fromCurve(equityCurve.map { it.equity })
        val sharpeR = sharpe(equityCurve.map { it.equity }, annualizationFactor)
        val calmarR = calmar(finalRealized.add(finalUnrealized), drawdown)

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
        )
    }
}
