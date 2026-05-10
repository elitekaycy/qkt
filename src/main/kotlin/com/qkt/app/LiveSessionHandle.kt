package com.qkt.app

import com.qkt.execution.Trade
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

    /** Layers from active STACK plans that haven't triggered yet. */
    fun pendingStackLayerInfos(): List<OrderManager.PendingStackLayerInfo>

    /** Cancels all working orders and flattens any open position at market. */
    fun flatten()
}
