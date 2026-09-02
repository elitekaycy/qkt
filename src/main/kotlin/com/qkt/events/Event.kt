package com.qkt.events

import com.qkt.execution.OrderRequest
import com.qkt.execution.Trade
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.positions.Position
import com.qkt.strategy.Signal
import java.math.BigDecimal

/**
 * The root type for everything that flows through [com.qkt.bus.EventBus].
 *
 * Every event carries a [timestamp] and [sequenceId] stamped by the bus at publish time
 * — components downstream rely on these for deterministic ordering and replay.
 */
sealed interface Event {
    /** Bus-assigned clock time (millis since epoch) at the moment of publish. */
    val timestamp: Long

    /** Bus-assigned monotonic id — strictly increasing within a single bus instance. */
    val sequenceId: Long
}

/** A live market tick that should drive strategy logic. */
data class TickEvent(
    val tick: Tick,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event

/**
 * A tick used solely to warm up indicators before live signal evaluation begins.
 *
 * Strategies should ignore these — they're consumed by indicator infrastructure only.
 */
data class WarmupTickEvent(
    val tick: Tick,
    /** Source candle window used to synthesize this tick, when warmup came from bars. */
    val sourceTimeframeMs: Long? = null,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event

/** A completed candle published by the aggregator after its window closes. */
data class CandleEvent(
    val candle: Candle,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event

/** A completed candle from one exact broker/symbol/timeframe DSL stream. */
data class StreamCandleEvent(
    val broker: String,
    val timeframe: String,
    val candle: Candle,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event

/** A DSL stream candle after the owning strategy alias has completed evaluation. */
data class StrategyCandleEvaluatedEvent(
    val strategyId: String,
    val alias: String,
    val broker: String,
    val timeframe: String,
    val rulesEvaluated: Int,
    val candle: Candle,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event

/** A deterministic DSL rule edge, including signal-less actions such as LOG. */
data class RuleDecisionEvent(
    val strategyId: String,
    val decisionId: String,
    val ruleId: String,
    val strategyFingerprint: String,
    val ruleFingerprint: String,
    val conditionFingerprint: String,
    val conditionResult: Boolean,
    val alias: String,
    val broker: String,
    val timeframe: String,
    val signalCount: Int,
    val candle: Candle,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event

/** Links a deterministic DSL rule decision to the normalized order id it created. */
data class DecisionOrderLinkedEvent(
    val strategyId: String,
    val decisionId: String,
    val ruleId: String,
    val signalIndex: Int,
    val orderId: String,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event

/** What an accounted amount came from: an execution, a financing accrual, or a boot-time venue reconcile. */
enum class FillAccountingKind { EXECUTION, FINANCING, RECONCILE }

/** Post-accounting evidence for one complete or partial broker fill slice. */
data class FillAccountedEvent(
    val orderId: String,
    val strategyId: String,
    val symbol: String,
    val fillSliceId: String,
    val sourceFillSequenceId: Long,
    val cumulativeFilled: BigDecimal?,
    val modeledCommissionAccount: BigDecimal,
    val venueCostsAccount: BigDecimal,
    val totalCostsAccount: BigDecimal,
    val accountNativeRealized: BigDecimal,
    val strategyNativeRealized: BigDecimal,
    val nativeCurrency: String,
    val grossAccountRealized: BigDecimal,
    val grossStrategyAccountRealized: BigDecimal,
    val accountCurrency: String,
    val netAccountRealized: BigDecimal,
    val netStrategyAccountRealized: BigDecimal,
    val conversionRate: BigDecimal?,
    val conversionTimestampMs: Long?,
    val conversionSource: String?,
    val contractSize: BigDecimal?,
    val accountPositionBefore: Position?,
    val accountPositionAfter: Position?,
    val strategyPositionBefore: Position?,
    val strategyPositionAfter: Position?,
    val reducedExposure: Boolean,
    /** Leg the fill was routed to when the strategy book is leg-routed (#1071). */
    val legId: String? = null,
    /** How the fill landed in the strategy leg book (OPENED/CLOSED/NETTED). */
    val legAction: com.qkt.positions.StrategyPositionTracker.LegAction? = null,
    val partial: Boolean,
    /** Source of the amount; every accumulator folds all kinds, trade statistics only executions. */
    val kind: FillAccountingKind = FillAccountingKind.EXECUTION,
    /** Venue time of the execution (or accrual), distinct from the bus stamp in [timestamp]. */
    val executedAt: Long = 0L,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event

/** A strategy-produced trading intent. The risk engine and order manager react to these. */
data class SignalEvent(
    val signal: Signal,
    /** Strategy that produced the signal; blank only for legacy/manual publishes. */
    val strategyId: String = "",
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event

/** A normalized order request that has passed risk and is ready for broker submission. */
data class OrderEvent(
    val request: OrderRequest,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event

/**
 * Emitted when the risk engine vetoes an [OrderRequest].
 *
 * The [reason] is the human-readable risk-rule label (e.g. `"daily-loss-halt"`).
 */
data class RiskRejectedEvent(
    val request: OrderRequest,
    val reason: String,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event

/**
 * Emitted when a strategy signal is dropped before it becomes an order — e.g. its
 * portfolio child gate is inactive or an operator stop is in force. Without this event
 * such drops leave no trace at all (no order exists yet, so no [RiskRejectedEvent] fires).
 */
data class SignalSuppressedEvent(
    val signal: Signal,
    val strategyId: String,
    val reason: String,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event

/** A broker-acknowledged fill. P&L attribution and position tracking consume these. */
data class TradeEvent(
    val trade: Trade,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
    /** Strategy that owns the fill, or blank when the source cannot attribute it. */
    val strategyId: String = "",
) : Event
