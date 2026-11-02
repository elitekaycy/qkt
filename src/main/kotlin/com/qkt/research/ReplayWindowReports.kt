package com.qkt.research

import com.qkt.backtest.ReportBuilder
import com.qkt.backtest.TradeOutcome
import com.qkt.backtest.TradeRecord
import com.qkt.backtest.WindowReport
import com.qkt.backtest.WindowSamples
import com.qkt.common.Money
import com.qkt.events.FillAccountingKind
import com.qkt.instrument.InstrumentRegistry
import java.math.BigDecimal

/**
 * The `--metrics-window`/`--oos-split` reports of one replay (#1276, #1451). Each window is cut from the
 * same books as `global`: its equity metrics from its own full-resolution samples, its trade statistics from
 * the run's round trips that closed inside it, and its costs from the fills, financing accruals and roll
 * costs stamped inside it, so windows that partition the run add up to the global commission, swap,
 * funding and roll costs.
 */
internal class ReplayWindowReports(
    private val recorder: ReplayRecorder,
    private val financing: ReplayFinancing,
    private val instruments: InstrumentRegistry,
) {
    /** One window's report; [roundTrips] are the whole run's, so a trade spanning the boundary counts where it closed. */
    fun build(
        samples: WindowSamples,
        trades: List<TradeRecord>,
        roundTrips: List<TradeOutcome>,
        annualizationFactor: BigDecimal,
    ): WindowReport {
        val window = samples.window
        val windowTrades = trades.filter { window.contains(it.trade.timestamp) }
        val equityStart = samples.metrics.startingEquity()
        val equityEnd = samples.lastEquity ?: equityStart
        val accounted = recorder.causality().accountedFills.filter { window.contains(it.executedAt) }
        val executionCosts =
            accounted
                .filter { it.kind == FillAccountingKind.EXECUTION }
                .fold(BigDecimal.ZERO) { total, fill -> total.add(fill.totalCostsAccount) }
        val rollCosts =
            accounted
                .filter { it.kind == FillAccountingKind.COST }
                .fold(BigDecimal.ZERO) { total, cost -> total.subtract(cost.netStrategyAccountRealized) }
        val report =
            ReportBuilder
                .buildGlobal(
                    trades = windowTrades,
                    equityCurve = emptyList(),
                    finalRealized = equityEnd.subtract(equityStart),
                    finalUnrealized = Money.ZERO,
                    annualizationFactor = annualizationFactor,
                    metrics = samples.metrics,
                    commissionPaid = executionCosts,
                    swapPaid = financing.swapPaid(window),
                    dailyAdjustments = financing.dailyNet(window),
                    tradedNotional = tradedNotional(windowTrades, instruments),
                    roundTrips = roundTrips.filter { window.contains(it.closedAtMs) },
                ).copy(
                    rollCostsPaid = rollCosts,
                    fundingPaid = financing.fundingPaid(window),
                    dividendsPaid = financing.dividendsPaid(window),
                )
        return WindowReport(
            window = window,
            samples = samples.metrics.count,
            closingFills = windowTrades.count { it.reducedExposure },
            equityStart = equityStart.setScale(Money.SCALE, Money.ROUNDING),
            equityEnd = equityEnd.setScale(Money.SCALE, Money.ROUNDING),
            report = report,
        )
    }
}

/** Gross traded notional (price x |qty| x contractSize) across [trades], for turnover. */
internal fun tradedNotional(
    trades: List<TradeRecord>,
    instruments: InstrumentRegistry,
): BigDecimal =
    trades.fold(Money.ZERO) { acc, r ->
        val cs = instruments.lookup(r.trade.symbol)?.contractSize ?: BigDecimal.ONE
        acc.add(
            r.trade.price
                .multiply(r.trade.quantity.abs(), Money.CONTEXT)
                .multiply(cs, Money.CONTEXT),
        )
    }
