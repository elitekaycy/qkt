package com.qkt.cli.daemon.limits

import com.qkt.app.DailyLimitReading
import com.qkt.app.LiveSessionHandle
import com.qkt.app.OrderManager
import com.qkt.execution.Trade
import com.qkt.risk.HaltScope
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** A session whose [reading] a test sets, counting what the hard limit's guard reads and does to it. */
internal class ScriptedSession : LiveSessionHandle {
    @Volatile var reading = DailyLimitReading("stuck", BigDecimal("-50"), BigDecimal.ZERO, emptyMap(), 1, false)

    @Volatile var answers = true
    val reads = AtomicInteger()

    @Volatile var flattens = 0

    @Volatile var halts = 0

    override val running = true
    override val droppedTicks = 0L

    override fun stop() {}

    override fun awaitTermination(timeout: Duration) = true

    override fun recentTrades() = emptyList<Trade>()

    override fun pendingStackLayerInfos() = emptyList<OrderManager.PendingStackLayerInfo>()

    override fun halt(
        reason: String,
        scope: HaltScope,
    ) {
        halts++
    }

    override fun flatten() {
        flattens++
    }

    override fun limitReading(): DailyLimitReading {
        reads.incrementAndGet()
        check(answers) { "engine did not answer" }
        return reading
    }
}
