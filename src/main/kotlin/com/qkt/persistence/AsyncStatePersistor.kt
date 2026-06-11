package com.qkt.persistence

import com.qkt.execution.OrderRequest
import com.qkt.positions.LegBook
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Decorator that runs [delegate]'s writes on a single-threaded daemon executor with a
 * bounded queue. Reads stay synchronous (booted-strategy preload + reconcile run before
 * the engine takes ticks, so they're not on a hot path).
 *
 * Why: the bus is single-threaded; sync writes to disk land on the dispatch thread. For
 * high-event-rate strategies (sub-second order cadence) the ~5-15ms `Files.move` per
 * save accumulates into observable latency. This decorator decouples disk I/O from the
 * bus.
 *
 * Trade-off: a crash between "engine mutated state" and "executor drained the queue"
 * loses up to [queueCapacity] in-flight writes. For typical trade-event rates the
 * window is < 1 second. The bus's single-threaded ordering guarantee is preserved (the
 * executor processes submissions in FIFO order).
 *
 * Closing the executor with [close] is a soft drain — pending writes complete; new
 * submissions are rejected after.
 */
class AsyncStatePersistor(
    private val delegate: StatePersistor,
    queueCapacity: Int = 1024,
    shutdownTimeoutMs: Long = 5_000L,
) : StatePersistor,
    AutoCloseable {
    private val log = LoggerFactory.getLogger(AsyncStatePersistor::class.java)

    private val callerRunsCount: java.util.concurrent.atomic.AtomicLong =
        java.util.concurrent.atomic
            .AtomicLong(0)

    private val executor: ThreadPoolExecutor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(queueCapacity),
            { r -> Thread(r, "qkt-persistence-async").apply { isDaemon = true } },
        ).also { it.rejectedExecutionHandler = CountingCallerRunsPolicy(callerRunsCount) }

    private val shutdownTimeoutMs: Long = shutdownTimeoutMs

    /** Current depth of the pending-write queue. Operators can watch for sustained high values. */
    val queueSize: Int get() = executor.queue.size

    /** Cumulative count of writes that fell back to caller-runs because the queue was full. */
    val callerRunsTotal: Long get() = callerRunsCount.get()

    private class CountingCallerRunsPolicy(
        private val counter: java.util.concurrent.atomic.AtomicLong,
    ) : java.util.concurrent.RejectedExecutionHandler {
        private val delegate = ThreadPoolExecutor.CallerRunsPolicy()

        override fun rejectedExecution(
            r: Runnable,
            executor: ThreadPoolExecutor,
        ) {
            counter.incrementAndGet()
            delegate.rejectedExecution(r, executor)
        }
    }

    private fun submit(
        label: String,
        action: () -> Unit,
    ) {
        if (executor.isShutdown) {
            log.warn("$label dropped: executor is shut down")
            return
        }
        try {
            executor.execute {
                runCatching { action() }
                    .onFailure { e -> log.warn("$label failed: ${e.message}") }
            }
        } catch (e: RejectedExecutionException) {
            log.warn("$label rejected by executor: ${e.message}")
        }
    }

    override fun saveLegBook(
        strategyId: String,
        symbol: String,
        legBook: LegBook,
    ) {
        // Snapshot synchronously in the caller's thread. PositionLeg is immutable; the
        // copy is cheap (a few legs at most). The executor receives a frozen view.
        val snapshot = LegBook(symbol).apply { legBook.all().forEach { add(it) } }
        submit("saveLegBook $strategyId/$symbol") {
            delegate.saveLegBook(strategyId, symbol, snapshot)
        }
    }

    override fun loadLegBook(
        strategyId: String,
        symbol: String,
    ): PersistedLegBook? = delegate.loadLegBook(strategyId, symbol)

    override fun saveBracketPairs(
        strategyId: String,
        pairs: List<BracketPair>,
    ) {
        val snapshot = pairs.toList()
        submit("saveBracketPairs $strategyId") {
            delegate.saveBracketPairs(strategyId, snapshot)
        }
    }

    override fun loadBracketPairs(strategyId: String): List<BracketPair> = delegate.loadBracketPairs(strategyId)

    override fun savePendingOrders(
        strategyId: String,
        orders: Map<String, OrderRequest>,
    ) {
        val snapshot = orders.toMap()
        submit("savePendingOrders $strategyId") {
            delegate.savePendingOrders(strategyId, snapshot)
        }
    }

    override fun loadPendingOrders(strategyId: String): Map<String, OrderRequest> =
        delegate.loadPendingOrders(strategyId)

    override fun savePendingStacks(
        strategyId: String,
        perPrimary: Map<String, PersistedTierState>,
    ) {
        val snapshot = perPrimary.toMap()
        submit("savePendingStacks $strategyId") {
            delegate.savePendingStacks(strategyId, snapshot)
        }
    }

    override fun loadPendingStacks(strategyId: String): Map<String, PersistedTierState> =
        delegate.loadPendingStacks(strategyId)

    override fun saveOcoLegs(
        strategyId: String,
        legs: List<PersistedOcoLeg>,
    ) {
        val snapshot = legs.toList()
        submit("saveOcoLegs $strategyId") {
            delegate.saveOcoLegs(strategyId, snapshot)
        }
    }

    override fun loadOcoLegs(strategyId: String): List<PersistedOcoLeg> = delegate.loadOcoLegs(strategyId)

    override fun saveRiskState(
        strategyId: String,
        state: PersistedRiskState,
    ) {
        submit("saveRiskState($strategyId)") { delegate.saveRiskState(strategyId, state) }
    }

    override fun loadRiskState(strategyId: String): PersistedRiskState? = delegate.loadRiskState(strategyId)

    override fun savePnl(
        strategyId: String,
        state: PersistedPnl,
    ) {
        submit("savePnl($strategyId)") { delegate.savePnl(strategyId, state) }
    }

    override fun loadPnl(strategyId: String): PersistedPnl? = delegate.loadPnl(strategyId)

    override fun clearStrategy(strategyId: String) {
        submit("clearStrategy $strategyId") { delegate.clearStrategy(strategyId) }
    }

    /**
     * Drain pending writes and shut down the executor. Blocks up to [shutdownTimeoutMs];
     * after the deadline, in-flight writes are abandoned with a warning log.
     */
    override fun close() {
        executor.shutdown()
        if (!executor.awaitTermination(shutdownTimeoutMs, TimeUnit.MILLISECONDS)) {
            val drained = executor.shutdownNow()
            log.warn("AsyncStatePersistor close timed out; ${drained.size} write(s) abandoned")
        }
    }

    /** Test seam: flush all pending writes synchronously. Blocks up to [timeoutMs]. */
    fun awaitDrain(timeoutMs: Long = 5_000L): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        executor.execute { latch.countDown() }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }
}
