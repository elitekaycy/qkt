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

    fun requestStop() {
        if (!stopping.compareAndSet(false, true)) return
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
            ),
        )
    }

    fun stop() {
        requestStop()
        if (!stopFinishing.compareAndSet(false, true)) return
        if (!terminated.await(drainGraceMs + 500L, TimeUnit.MILLISECONDS)) {
            running.set(false)
            thread.interrupt()
        }
        // Release venue-side lifecycle resources (MT5 pollers, Bybit reconcilers)
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
