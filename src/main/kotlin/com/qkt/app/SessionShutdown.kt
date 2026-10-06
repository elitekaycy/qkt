package com.qkt.app

import com.qkt.app.LiveSession.Companion.STOP_DRAIN_GRACE_MS
import com.qkt.broker.Broker
import com.qkt.dsl.compile.CandleHub
import com.qkt.marketdata.TickFeed
import com.qkt.observe.insights.BrokerStatePoller
import com.qkt.risk.RiskState
import com.qkt.strategy.Strategy
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** How long a stop waits for a flatten in progress before interrupting the engine thread. */
internal const val STOP_FLATTEN_GRACE_MS: Long = 30_000L

/**
 * Stops a live session in order: stop the inputs (feed, pollers, heartbeat), let the engine loop
 * drain what is already queued, then release brokers and announce the stop. A session with venue
 * brokers gets a drain grace so an in-flight fill is still booked, e.g. `stop()` on an MT5
 * session waits up to 2.5s for the loop; a paper session does not wait for a drain at all.
 */
internal class SessionShutdown(
    private val strategies: List<Pair<String, Strategy>>,
    private val mailbox: EngineMailbox,
    private val thread: Thread,
    private val feedThread: Thread,
    private val feed: TickFeed,
    private val brokerStatePoller: BrokerStatePoller?,
    private val scheduleHeartbeat: ScheduledExecutorService,
    private val equityPoller: ScheduledExecutorService?,
    private val builtBrokers: List<Broker>,
    private val riskState: RiskState,
    private val pipelineCandleHub: CandleHub,
    private val sessionNotifier: SessionNotifier,
    private val insights: InsightsLifecycle,
) {
    private val running = mailbox.running
    private val stopping = mailbox.stopping
    private val stopFinishing = mailbox.stopFinishing
    private val control = mailbox.control
    private val tickQueue = mailbox.tickQueue
    private val terminated = mailbox.terminated

    private val drainGraceMs = if (builtBrokers.isEmpty()) 0L else STOP_DRAIN_GRACE_MS
    private val log = org.slf4j.LoggerFactory.getLogger(LiveSession::class.java)

    @Volatile
    private var stopRequestedNanos = 0L

    fun requestStop() {
        if (!stopping.compareAndSet(false, true)) return
        stopRequestedNanos = System.nanoTime()
        feedThread.interrupt()
        runCatching { feed.close() }
        runCatching { brokerStatePoller?.close() }
        // Stop the schedule heartbeat thread so it doesn't outlive the session.
        runCatching {
            scheduleHeartbeat.shutdownNow()
            scheduleHeartbeat.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
        }
        // Stop the broker-equity poller (#352) so it doesn't outlive the session.
        runCatching { equityPoller?.shutdownNow() }
        tickQueue.clear()
        control.put(
            Inbound.GracefulStop(
                deadlineNanos = System.nanoTime() + drainGraceMs * 1_000_000L,
                graceNanos = drainGraceMs * 1_000_000L,
            ),
        )
    }

    /**
     * A flatten still queued or running when the drain grace runs out is the operator's emergency
     * exit: interrupting it mid venue read used to fault it with nothing closed (#1357). Wait for it,
     * bounded by [STOP_FLATTEN_GRACE_MS]; true when the loop then ended on its own.
     */
    private fun awaitFlatten(): Boolean {
        // Also a flatten that just finished: the loop is giving its closes' fills a drain grace.
        val flattenedSinceStop = mailbox.lastFlattenEndNanos.get() - stopRequestedNanos > 0
        if (mailbox.pendingFlattens.get() <= 0 && !flattenedSinceStop) return false
        log.warn("stop is waiting up to {}ms for a flatten still in progress", STOP_FLATTEN_GRACE_MS)
        return terminated.await(STOP_FLATTEN_GRACE_MS, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        requestStop()
        if (!stopFinishing.compareAndSet(false, true)) return
        if (!terminated.await(drainGraceMs + 500L, TimeUnit.MILLISECONDS) && !awaitFlatten()) {
            running.set(false)
            thread.interrupt()
        }
        // Release venue-side lifecycle resources (MT5 pollers, gateway sessions)
        // so a long-running daemon cycling strategies doesn't accumulate threads.
        for (b in builtBrokers) runCatching { b.shutdown() }
        runCatching { riskState.persistAnchorsIfDirty() }
        // Drop hub registrations attributed to this session's strategies so
        // their aggregators and listener closures fall out of scope.
        for ((strategyId, _) in strategies) {
            runCatching { pipelineCandleHub.unregister(strategyId) }
        }
        sessionNotifier.strategiesStopped()
        insights.strategiesStopped()
    }
}
