package com.qkt.events

import com.qkt.execution.OrderRequest
import com.qkt.execution.Trade
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal

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
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event

/** A completed candle published by the aggregator after its window closes. */
data class CandleEvent(
    val candle: Candle,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event

/** A strategy-produced trading intent. The risk engine and order manager react to these. */
data class SignalEvent(
    val signal: Signal,
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

/** A broker-acknowledged fill. P&L attribution and position tracking consume these. */
data class TradeEvent(
    val trade: Trade,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event
