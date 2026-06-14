package com.qkt.app

import com.qkt.execution.Trade
import com.qkt.notify.StrategySummary
import java.math.BigDecimal
import java.time.Duration

/**
 * Operator handle for a running [LiveSession].
 *
 * Exposed through the observability HTTP surface and the daemon control plane — lets
 * `qkt stop`, `qkt status`, and `qkt flatten` reach into a live session without
 * coupling them to the full [LiveSession] type.
 */
interface LiveSessionHandle {
    /** `true` while the session's tick loop is running. */
    val running: Boolean

    /** Count of ticks dropped because the strategy callback couldn't keep up. */
    val droppedTicks: Long

    /**
     * Depth of the inbound engine queue (control events + buffered ticks). A growing
     * value flags a stalled or slow engine thread before it becomes dropped ticks.
     */
    fun inboundQueueDepth(): Int = 0

    /** Symbols whose market data is currently stale, with quote age in ms (#395). */
    fun staleSymbols(): Map<String, Long> = emptyMap()

    /**
     * Engine-vs-broker truth comparison (#400, FIA §2.1): per-symbol net position
     * deltas plus equity on both sides. Run daily via `qkt reconcile` — slow silent
     * state drift (the hedge-accumulation bug class) is exactly what this catches.
     * Default null for handles without a broker view.
     */
    fun reconcile(): ReconcileReport? = null

    /** Initiates graceful shutdown. Use [awaitTermination] to wait for completion. */
    fun stop()

    /** Blocks up to [timeout] for shutdown. Returns `true` iff the session terminated in time. */
    fun awaitTermination(timeout: Duration): Boolean

    /** Snapshot of the most recently observed trades — bounded ring, oldest first. */
    fun recentTrades(): List<Trade>

    /**
     * Net open positions held for [strategyId], one entry per symbol (positive qty = long,
     * negative = short). Read by `qkt status` so an operator can see what a running strategy
     * holds. Defaults to empty for handles that are not full live sessions.
     */
    fun positionsFor(strategyId: String): List<com.qkt.positions.Position> = emptyList()

    /**
     * Per-strategy daily-summary rows for this session. The daemon's single
     * [com.qkt.notify.DailySummaryScheduler] aggregates these across all sessions.
     * Reading them snapshots and resets this session's daily tracker — call once per fire.
     * Defaults to empty for handles that are not full live sessions.
     */
    fun dailySummaryRows(): List<StrategySummary> = emptyList()

    /** Layers from active STACK plans that haven't triggered yet. */
    fun pendingStackLayerInfos(): List<OrderManager.PendingStackLayerInfo>

    /**
     * Operator halt: stop submitting NEW orders for this session (existing positions keep
     * being managed). Default is a no-op so non-daemon handles (tests, replay) need not
     * implement it; the live daemon session overrides it to drive its risk state.
     */
    fun halt(reason: String) {}

    /** Reverse [halt]: re-enable new-order submission. Default no-op. */
    fun resume() {}

    /** Whether this session is currently halted (operator halt or a risk auto-halt). */
    fun isHalted(): Boolean = false

    /** Cancels all working orders and flattens any open position at market. */
    fun flatten()

    /**
     * Snapshot of pipeline latency observations (#150). Defaults to a disabled-report so
     * non-live handles don't need to override. Live sessions return `pipeline.latency.snapshot()`.
     */
    fun latencySnapshot(): com.qkt.observability.LatencyRegistry.Report =
        com.qkt.observability.LatencyRegistry
            .Report(enabled = false, strategies = emptyMap())

    /**
     * Per-strategy alias → broker label map for DSL-compiled strategies (#139). Lets
     * `qkt status --deep` show which broker each declared stream routes to. Default is
     * empty for non-live handles or plain strategies.
     */
    fun streamBrokers(): Map<String, String> = emptyMap()

    /**
     * Current P&L scalars for [strategyId] — equity, balance, realized, unrealized. Defaults
     * to zero for handles that aren't full live sessions (tests, replay); the live daemon
     * session overrides it to read from its strategy P&L tracker so `/status` shows real P&L.
     */
    fun pnlSnapshot(strategyId: String): SessionPnl = SessionPnl.ZERO
}

/** A point-in-time P&L reading for one strategy, surfaced through `/status`. */
data class SessionPnl(
    val equity: BigDecimal,
    val balance: BigDecimal,
    val realized: BigDecimal,
    val unrealized: BigDecimal,
) {
    companion object {
        val ZERO = SessionPnl(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
    }
}

/** One symbol where engine and broker disagree on net quantity. */
data class PositionDelta(
    val symbol: String,
    val engineQty: java.math.BigDecimal,
    val brokerQty: java.math.BigDecimal,
)

/** Result of an engine-vs-broker reconcile pass. Clean when [deltas] is empty. */
data class ReconcileReport(
    val deltas: List<PositionDelta>,
    val engineEquity: java.math.BigDecimal,
    val brokerEquity: java.math.BigDecimal?,
) {
    val clean: Boolean get() = deltas.isEmpty()
}
