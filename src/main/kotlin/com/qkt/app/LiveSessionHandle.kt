package com.qkt.app

import com.qkt.execution.Trade
import com.qkt.notify.StrategySummary
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

    /** Initiates graceful shutdown. Use [awaitTermination] to wait for completion. */
    fun stop()

    /** Blocks up to [timeout] for shutdown. Returns `true` iff the session terminated in time. */
    fun awaitTermination(timeout: Duration): Boolean

    /** Snapshot of the most recently observed trades — bounded ring, oldest first. */
    fun recentTrades(): List<Trade>

    /**
     * Per-strategy daily-summary rows for this session. The daemon's single
     * [com.qkt.notify.DailySummaryScheduler] aggregates these across all sessions.
     * Reading them snapshots and resets this session's daily tracker — call once per fire.
     * Defaults to empty for handles that are not full live sessions.
     */
    fun dailySummaryRows(): List<StrategySummary> = emptyList()

    /** Layers from active STACK plans that haven't triggered yet. */
    fun pendingStackLayerInfos(): List<OrderManager.PendingStackLayerInfo>

    /** Cancels all working orders and flattens any open position at market. */
    fun flatten()
}
