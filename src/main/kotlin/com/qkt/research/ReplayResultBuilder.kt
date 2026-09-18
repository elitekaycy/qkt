package com.qkt.research

import com.qkt.app.TradingPipeline
import com.qkt.backtest.BacktestResult
import com.qkt.backtest.EquityMetrics
import com.qkt.backtest.ReportBuilder
import com.qkt.backtest.RunawayBreakerReport
import com.qkt.backtest.SampleCadence
import com.qkt.backtest.TradeRecord
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.instrument.InstrumentRegistry
import com.qkt.pnl.SwapFinancingBook
import com.qkt.strategy.Strategy
import java.math.BigDecimal

/**
 * Turns a replay's books, samplers and recorder into a [BacktestResult]. Reads state only, so a
 * snapshot is valid mid-replay or at the end; cold path, called once per snapshot.
 */
internal class ReplayResultBuilder(
    private val strategies: List<Pair<String, Strategy>>,
    private val books: ReplayBooks,
    private val recorder: ReplayRecorder,
    private val analytics: ReplayAnalytics,
    private val swapBook: SwapFinancingBook,
    private val pipeline: TradingPipeline,
    private val instruments: InstrumentRegistry,
    private val cadence: SampleCadence,
    private val candleWindow: TimeWindow?,
    private val calendar: TradingCalendar,
    private val latencyEnabled: Boolean,
    private val enforceLiveBreakers: Boolean,
    private val runawayMaxRoundTrips: Int,
    private val runawayRoundTripWindowMs: Long,
    private val runawayMaxRejections: Int,
    private val runawayRejectionWindowMs: Long,
) {
    /** Build the result, with [ticksIngested] as the attempted feed tick count. */
    fun build(ticksIngested: Long): BacktestResult {
        val collector = analytics.collector
        val tradeRecords = recorder.tradeRecords
        val pnl = books.pnl
        val strategyPnL = books.strategyPnL
        val commissionBook = books.commissionBook
        val annualizationFactor = annualizationFactorFor(collector.globalMetrics())
        val globalReport =
            ReportBuilder.buildGlobal(
                trades = tradeRecords,
                equityCurve = collector.global(),
                finalRealized = pnl.realizedTotal(),
                finalUnrealized = pnl.unrealizedTotal(),
                annualizationFactor = annualizationFactor,
                metrics = collector.globalMetrics(),
                commissionPaid = commissionBook.total(),
                swapPaid = swapBook.totalPaid(),
                dailyAdjustments = swapBook.dailyNet(),
                tradedNotional = tradedNotional(tradeRecords),
            )
        val perStrategy =
            strategies.associate { (id, _) ->
                id to
                    ReportBuilder.buildPerStrategy(
                        strategyId = id,
                        trades = tradeRecords.filter { it.strategyId == id },
                        equityCurve = collector.forStrategy(id),
                        finalRealized = strategyPnL.realizedFor(id),
                        finalUnrealized = strategyPnL.unrealizedTotalFor(id),
                        annualizationFactor = annualizationFactor,
                        metrics = collector.metricsFor(id),
                        commissionPaid = commissionBook.totalFor(id),
                        swapPaid = swapBook.totalPaidFor(id),
                        dailyAdjustments = swapBook.dailyNetFor(id),
                        tradedNotional = tradedNotional(tradeRecords.filter { it.strategyId == id }),
                    )
            }
        return BacktestResult(
            trades = tradeRecords.toList(),
            rejections = recorder.rejections.toList(),
            halts = recorder.halts.toList(),
            finalPositions = books.positions.allPositions(),
            global = globalReport,
            perStrategy = perStrategy,
            cadence = cadence,
            latencyReport = if (latencyEnabled) pipeline.latency.snapshot() else null,
            conditionalAutocorr = analytics.autocorr.snapshot(),
            bookAnalytics = analytics.bookReturns.result(),
            bookRisk = analytics.bookRiskMonitor.result(annualizationFactor),
            accounting = books.accounting.snapshot(),
            finalPositionsByStrategy = books.strategyPositions.allByStrategy(),
            inputSummary =
                recorder.inputSummary(
                    attemptedFeedTicks = ticksIngested,
                    malformedTicks = pipeline.malformedTickCount.get(),
                    droppedLateTicks = pipeline.droppedLateTicks(),
                ),
            runawayBreaker =
                RunawayBreakerReport(
                    enforceLiveBreakers = enforceLiveBreakers,
                    maxRoundTrips = runawayMaxRoundTrips,
                    roundTripWindowMs = runawayRoundTripWindowMs,
                    maxRejections = runawayMaxRejections,
                    rejectionWindowMs = runawayRejectionWindowMs,
                    trips = recorder.breakerTrips.toList(),
                ),
            causality = recorder.causality(),
        )
    }

    /** Gross traded notional (price x |qty| x contractSize) across [trades], for turnover. */
    private fun tradedNotional(trades: List<TradeRecord>): BigDecimal =
        trades.fold(Money.ZERO) { acc, r ->
            val cs = instruments.lookup(r.trade.symbol)?.contractSize ?: BigDecimal.ONE
            acc.add(
                r.trade.price
                    .multiply(r.trade.quantity.abs(), Money.CONTEXT)
                    .multiply(cs, Money.CONTEXT),
            )
        }

    private fun annualizationFactorFor(metrics: EquityMetrics): BigDecimal {
        if (cadence == SampleCadence.CANDLE_CLOSE && candleWindow != null) {
            return calendar.tradingPeriodsPerYear(candleWindow)
        }
        if (metrics.count < 2) return BigDecimal("252")
        val first = metrics.firstTimestamp() ?: return BigDecimal("252")
        val spanMs = metrics.lastTimestamp() - first
        if (spanMs <= 0L) return BigDecimal("252")
        val avgIntervalMs = BigDecimal(spanMs).divide(BigDecimal(metrics.count - 1), Money.CONTEXT)
        val msPerYear = BigDecimal("31557600000")
        return msPerYear.divide(avgIntervalMs, Money.CONTEXT)
    }
}
