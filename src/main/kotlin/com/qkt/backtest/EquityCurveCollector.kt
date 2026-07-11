package com.qkt.backtest

import com.qkt.bus.EventBus
import com.qkt.events.BrokerEvent
import com.qkt.events.CandleEvent
import com.qkt.events.TickEvent
import com.qkt.pnl.PnLProvider
import com.qkt.pnl.StrategyPnL
import java.math.BigDecimal

class EquityCurveCollector(
    private val cadence: SampleCadence,
    bus: EventBus,
    private val pnl: PnLProvider,
    private val strategyPnL: StrategyPnL,
    strategyIds: List<String>,
    curveCap: Int = DEFAULT_CURVE_CAP,
    /**
     * Symbols expected at each candle boundary. When nonempty, candle-close cadence
     * samples once after every symbol has closed that boundary instead of once per symbol.
     */
    private val candleSymbols: Set<String> = emptySet(),
    /**
     * Account starting balance — anchors the curve at true equity. A 0-based PnL curve
     * makes every derived metric capital-blind: a $500 peak dipping to $250 reads as a
     * 50% drawdown when the account is $10k (really 2.5%), and Sharpe's returns are
     * returns-on-profit instead of returns-on-capital.
     */
    private val startingBalance: BigDecimal = BigDecimal.ZERO,
) {
    private val globalMetricsAcc = EquityMetrics()
    private val globalCurve = DecimatedCurve(curveCap)
    private val strategyMetricsAcc: Map<String, EquityMetrics> = strategyIds.associateWith { EquityMetrics() }
    private val strategyCurve: Map<String, DecimatedCurve> = strategyIds.associateWith { DecimatedCurve(curveCap) }
    private var pendingCandleEndTime: Long? = null
    private val symbolsClosedAtBoundary = mutableSetOf<String>()

    init {
        when (cadence) {
            SampleCadence.CANDLE_CLOSE ->
                bus.subscribe<CandleEvent> { e -> onCandleClose(e) }
            SampleCadence.TICK ->
                bus.subscribe<TickEvent> { e -> sample(e.tick.timestamp) }
            SampleCadence.FILL ->
                bus.subscribe<BrokerEvent.OrderFilled> { e -> sample(e.timestamp) }
        }
    }

    /** Bounded chart view of the global equity curve. Exact metrics come from [globalMetrics]. */
    fun global(): List<EquitySample> = globalCurve.snapshot()

    /** Bounded chart view of a strategy's equity curve, or empty for an unknown strategy. */
    fun forStrategy(strategyId: String): List<EquitySample> = strategyCurve[strategyId]?.snapshot() ?: emptyList()

    /** Full-resolution global performance metrics (drawdown, Sharpe, drawdown periods). */
    fun globalMetrics(): EquityMetrics = globalMetricsAcc

    /** Full-resolution metrics for a strategy, or null for an unknown strategy. */
    fun metricsFor(strategyId: String): EquityMetrics? = strategyMetricsAcc[strategyId]

    private fun onCandleClose(event: CandleEvent) {
        if (candleSymbols.isEmpty()) {
            sample(event.candle.endTime)
            return
        }
        if (pendingCandleEndTime != event.candle.endTime) {
            pendingCandleEndTime = event.candle.endTime
            symbolsClosedAtBoundary.clear()
        }
        symbolsClosedAtBoundary.add(event.candle.symbol)
        if (!symbolsClosedAtBoundary.containsAll(candleSymbols)) return
        sample(event.candle.endTime)
        pendingCandleEndTime = null
        symbolsClosedAtBoundary.clear()
    }

    private fun sample(timestamp: Long) {
        val globalEquity: BigDecimal = startingBalance.add(pnl.realizedTotal()).add(pnl.unrealizedTotal())
        globalMetricsAcc.accept(timestamp, globalEquity)
        globalCurve.accept(EquitySample(timestamp, globalEquity))
        for ((strategyId, metrics) in strategyMetricsAcc) {
            val equity = strategyPnL.equityFor(strategyId)
            metrics.accept(timestamp, equity)
            strategyCurve.getValue(strategyId).accept(EquitySample(timestamp, equity))
        }
    }

    companion object {
        /**
         * Default cap on retained chart points per stream. High enough for a smooth curve, low
         * enough that a multi-million-tick run cannot exhaust memory. Metrics stay full-resolution
         * regardless — only the stored/displayed curve is thinned past this many points.
         */
        const val DEFAULT_CURVE_CAP: Int = 10_000
    }
}
