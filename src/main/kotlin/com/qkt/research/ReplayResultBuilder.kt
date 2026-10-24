package com.qkt.research

import com.qkt.app.TradingPipeline
import com.qkt.backtest.BacktestResult
import com.qkt.backtest.EquityMetrics
import com.qkt.backtest.ReportBuilder
import com.qkt.backtest.RunawayBreakerReport
import com.qkt.backtest.SampleCadence
import com.qkt.backtest.monthlyReturns
import com.qkt.backtest.tradeOutcomes
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.SessionOverlap
import com.qkt.common.TradingCalendar
import com.qkt.instrument.InstrumentRegistry
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
    private val financing: ReplayFinancing,
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
    private val windowReports = ReplayWindowReports(recorder, financing, instruments)

    /** Build the result, with [ticksIngested] as the attempted feed tick count. */
    fun build(ticksIngested: Long): BacktestResult {
        val collector = analytics.collector
        val tradeRecords = recorder.tradeRecords
        val pnl = books.pnl
        val strategyPnL = books.strategyPnL
        val commissionBook = books.commissionBook
        val annualizationFactor =
            annualizationFactorFor(collector.globalMetrics(), SessionOverlap.ALL, analytics.candleSymbols)
        val roundTrips = tradeOutcomes(tradeRecords)
        val globalReport =
            ReportBuilder.buildGlobal(
                trades = tradeRecords,
                equityCurve = collector.global(),
                finalRealized = pnl.realizedTotal(),
                finalUnrealized = pnl.unrealizedTotal(),
                annualizationFactor = annualizationFactor,
                metrics = collector.globalMetrics(),
                commissionPaid = commissionBook.total().add(recorder.venueCostsPaid()),
                swapPaid = financing.swapPaid(),
                dailyAdjustments = financing.dailyNet(),
                tradedNotional = tradedNotional(tradeRecords, instruments),
                roundTrips = roundTrips,
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
                        commissionPaid = commissionBook.totalFor(id).add(recorder.venueCostsPaid(id)),
                        swapPaid = financing.swapPaid(id),
                        dailyAdjustments = financing.dailyNet(id),
                        tradedNotional = tradedNotional(tradeRecords.filter { it.strategyId == id }, instruments),
                    )
            }
        return BacktestResult(
            trades = tradeRecords.toList(),
            rejections = recorder.rejections.toList(),
            halts = recorder.halts.toList(),
            finalPositions = books.positions.allPositions(),
            global = globalReport.copy(rollCostsPaid = recorder.rollCostsPaid(), fundingPaid = financing.fundingPaid()),
            perStrategy =
                perStrategy.mapValues { (id, report) ->
                    report.copy(rollCostsPaid = recorder.rollCostsPaid(id), fundingPaid = financing.fundingPaid(id))
                },
            cadence = cadence,
            latencyReport = if (latencyEnabled) pipeline.latency.snapshot() else null,
            conditionalAutocorr = analytics.autocorr.snapshot(),
            bookAnalytics = analytics.bookReturns.result(),
            bookRisk =
                analytics.bookRiskMonitor.result(
                    annualizationFactorFor(collector.globalMetrics(), SessionOverlap.ANY, calendar.runSymbols),
                ),
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
            dailyEquity = collector.dailyEquity(),
            monthlyReturns = monthlyReturns(collector.dailyEquity()),
            windows =
                collector.windows().map {
                    windowReports.build(
                        it,
                        tradeRecords,
                        roundTrips,
                        annualizationFactor,
                    )
                },
            rolls = books.rolls.entries,
            contractFills = books.contractFills.entries,
            settlements = books.settlements.entries,
            structures = books.structures.entries,
            marginDaily = books.marginDaily.rows,
            liquidations = books.liquidator.log.entries,
            executionFacts = books.execution.snapshot(),
        )
    }

    /**
     * Samples a year for a candle-close sampler over [symbols]: the run's equity curve waits for every candle symbol
     * ([SessionOverlap.ALL]), the book samplers take any symbol's close ([SessionOverlap.ANY]). Other cadences
     * estimate it from the sample spacing.
     */
    private fun annualizationFactorFor(
        metrics: EquityMetrics,
        overlap: SessionOverlap,
        symbols: Collection<String>,
    ): BigDecimal {
        if (cadence == SampleCadence.CANDLE_CLOSE && candleWindow != null) {
            return calendar.periodsPerYear(symbols, candleWindow, overlap)
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
