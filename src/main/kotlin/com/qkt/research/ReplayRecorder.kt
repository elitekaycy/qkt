package com.qkt.research

import com.qkt.accounting.ConvertedMoney
import com.qkt.app.OrderManager
import com.qkt.backtest.FillState
import com.qkt.backtest.ReplayCausalityReport
import com.qkt.backtest.ReplayInputReport
import com.qkt.backtest.TradeRecord
import com.qkt.bus.EventBus
import com.qkt.events.CandleEvent
import com.qkt.events.DecisionOrderLinkedEvent
import com.qkt.events.FillAccountedEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.RuleDecisionEvent
import com.qkt.events.StrategyCandleEvaluatedEvent
import com.qkt.events.StreamCandleEvent
import com.qkt.events.TickEvent
import com.qkt.events.WarmupTickEvent
import com.qkt.execution.Trade
import com.qkt.risk.RunawayBreakerTrip
import com.qkt.strategy.Signal

/**
 * Everything a replay writes down as it runs: the trade tape, rejections, halts, breaker trips, the
 * causality events and the input counters that end up in the [com.qkt.backtest.BacktestResult], plus
 * the research [TapeEvent] buffer. It only appends; the report is assembled from it on demand.
 */
internal class ReplayRecorder(
    private val initialTimestamp: Long,
) {
    val tradeRecords = mutableListOf<TradeRecord>()
    val rejections = mutableListOf<RiskRejectedEvent>()
    private val approvedOrders = mutableListOf<OrderEvent>()
    private val ruleDecisions = mutableListOf<RuleDecisionEvent>()
    private val decisionOrderLinks = mutableListOf<DecisionOrderLinkedEvent>()
    private val accountedFills = mutableListOf<FillAccountedEvent>()
    val halts = mutableListOf<RiskEvent.Halted>()
    val breakerTrips = mutableListOf<RunawayBreakerTrip>()
    private val tape = mutableListOf<TapeEvent>()
    private var liveTicksProcessed = 0L
    private var warmupTicksProcessed = 0L
    private var warmupCandlesEmitted = 0L
    private var liveCandlesEmitted = 0L
    private val streamCandlesEmitted = mutableMapOf<String, Long>()
    private val strategyCandleEvaluations = mutableMapOf<String, Long>()

    /** Subscribe the causality and input counters; called first, before any pipeline subscriber. */
    fun subscribe(bus: EventBus) {
        bus.subscribe<OrderEvent>(approvedOrders::add)
        bus.subscribe<RuleDecisionEvent>(ruleDecisions::add)
        bus.subscribe<DecisionOrderLinkedEvent>(decisionOrderLinks::add)
        bus.subscribe<FillAccountedEvent>(accountedFills::add)
        bus.subscribe<TickEvent> { liveTicksProcessed++ }
        bus.subscribe<WarmupTickEvent> { warmupTicksProcessed++ }
        bus.subscribe<CandleEvent> { event ->
            if (event.candle.endTime <= initialTimestamp) {
                warmupCandlesEmitted++
            } else {
                liveCandlesEmitted++
            }
        }
        bus.subscribe<StreamCandleEvent> { event ->
            val key = "${event.broker}:${event.candle.symbol.substringAfter(':')}:${event.timeframe}"
            streamCandlesEmitted[key] = (streamCandlesEmitted[key] ?: 0L) + 1L
        }
        bus.subscribe<StrategyCandleEvaluatedEvent> { event ->
            val symbol = event.candle.symbol.substringAfter(':')
            val key = "${event.strategyId}:${event.alias}:${event.broker}:$symbol:${event.timeframe}"
            strategyCandleEvaluations[key] = (strategyCandleEvaluations[key] ?: 0L) + 1L
        }
    }

    /** Book one accounted fill as a [TradeRecord] and a tape entry stamped [timestamp]. */
    fun recordFill(
        timestamp: Long,
        trade: Trade,
        converted: ConvertedMoney,
        strategyId: String,
        fillState: FillState,
        orderManager: OrderManager?,
    ) {
        val entryRisk =
            orderManager?.entryRiskForFill(
                clientOrderId = trade.orderId,
                quantity = trade.quantity,
                fillPrice = trade.price,
                symbol = trade.symbol,
            )
        tradeRecords.add(
            TradeRecord(
                trade = trade,
                realized = fillState.netAccountRealized,
                strategyId = strategyId,
                orderType =
                    orderManager
                        ?.getOrder(trade.orderId)
                        ?.request
                        ?.javaClass
                        ?.simpleName,
                riskUsd = entryRisk?.riskUsd,
                stopLossPrice = entryRisk?.protection?.stopLoss,
                takeProfitPrice = entryRisk?.protection?.takeProfit,
                nativeRealized = converted.native.amount,
                nativeCurrency = converted.native.normalizedCurrency,
                accountRealized = converted.account.amount,
                accountCurrency = converted.account.normalizedCurrency,
                fxRate = converted.conversion?.rate,
                fxRateTimestamp = converted.conversion?.timestamp,
                fxSource = converted.conversion?.source,
                accountPositionBefore = fillState.accountPositionBefore,
                accountPositionAfter = fillState.accountPositionAfter,
                strategyPositionBefore = fillState.strategyPositionBefore,
                strategyPositionAfter = fillState.strategyPositionAfter,
                contractSize = fillState.contractSize,
                reducedExposure = fillState.reducedExposure,
                legId = fillState.legId,
                legAction = fillState.legAction,
            ),
        )
        tape.add(TapeEvent.Filled(timestamp, trade, fillState.netAccountRealized, strategyId))
    }

    /** Book a risk rejection and its tape entry stamped [timestamp]. */
    fun recordRejection(
        timestamp: Long,
        e: RiskRejectedEvent,
    ) {
        rejections.add(e)
        tape.add(TapeEvent.Rejected(timestamp, e.request.symbol, e.reason))
    }

    /** Add a strategy signal to the research tape, stamped [timestamp]. */
    fun recordSignal(
        timestamp: Long,
        signal: Signal,
    ) {
        tape.add(TapeEvent.SignalEmitted(timestamp, signal))
    }

    /** Returns tape events accumulated since the last drain, then clears the buffer. */
    fun drainTape(): List<TapeEvent> {
        val out = tape.toList()
        tape.clear()
        return out
    }

    /** How much input the replay consumed, given the engine-owned tick counters. */
    fun inputSummary(
        attemptedFeedTicks: Long,
        malformedTicks: Long,
        droppedLateTicks: Long,
    ): ReplayInputReport =
        ReplayInputReport(
            attemptedFeedTicks = attemptedFeedTicks,
            liveTicks = liveTicksProcessed,
            warmupTicks = warmupTicksProcessed,
            warmupCandles = warmupCandlesEmitted,
            liveCandles = liveCandlesEmitted,
            malformedTicks = malformedTicks,
            droppedLateTicks = droppedLateTicks,
            streamCandles = streamCandlesEmitted.toSortedMap(),
            strategyCandleEvaluations = strategyCandleEvaluations.toSortedMap(),
        )

    /** The decision -> order -> fill chain recorded so far. */
    fun causality(): ReplayCausalityReport =
        ReplayCausalityReport(
            approvedOrders = approvedOrders.toList(),
            ruleDecisions = ruleDecisions.toList(),
            decisionOrderLinks = decisionOrderLinks.toList(),
            accountedFills = accountedFills.toList(),
        )
}
