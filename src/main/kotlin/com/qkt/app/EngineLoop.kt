package com.qkt.app

import com.qkt.app.LiveSession.Companion.QUEUE_POLL_MS
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.marketdata.MarketDataGate
import com.qkt.observe.EngineAuditJournal
import com.qkt.observe.OrderJournal
import com.qkt.strategy.Strategy
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * The single-consumer engine loop: the ONE thread that touches the bus, OrderManager,
 * positions, and the schedule runner. The tick feed, the heartbeat, the broker pollers
 * (via the bus), and the HTTP flatten all POST onto the [EngineMailbox]; this loop drains it
 * serially, restoring the "engine is single-threaded" invariant in live mode, e.g. a fill
 * published by an MT5 poller thread is queued and booked here, between two ticks.
 */
internal class EngineLoop(
    private val mailbox: EngineMailbox,
    private val pipeline: TradingPipeline,
    private val clock: Clock,
    private val bus: EventBus,
    private val strategies: List<Pair<String, Strategy>>,
    private val feedSymbols: List<String>,
    private val marketDataGate: MarketDataGate,
    private val candleCloseGraceMs: Long,
    private val mdcStrategy: String?,
    private val journal: OrderJournal?,
    private val auditJournal: EngineAuditJournal?,
    private val faults: EngineFaults,
    private val persistenceWatch: PersistenceHealthWatch,
    private val flatten: SessionFlatten,
    private val sessionNotifier: SessionNotifier,
) {
    // Logged under the session's category so existing log filters keep matching.
    private val log = LoggerFactory.getLogger(LiveSession::class.java)
    private val running = mailbox.running
    private val stopping = mailbox.stopping
    private val clearRuleEdgesAtStop = mailbox.clearRuleEdgesAtStop
    private val control = mailbox.control
    private val tickQueue = mailbox.tickQueue
    private val terminated = mailbox.terminated

    /** The `qkt-live-engine` daemon thread that runs this loop; the caller starts it. */
    fun newThread(): Thread = Thread({ run() }, "qkt-live-engine").apply { isDaemon = true }

    private fun onEngineFault(
        stage: String,
        t: Throwable,
    ) = faults.onEngineFault(stage, t)

    private fun processTick(msg: Inbound.FeedTick) {
        val latencyStartNanos = if (pipeline.latency.enabled) System.nanoTime() else 0L
        try {
            // Drive event-time from the tick being PROCESSED (not when it was
            // read off the feed) so a deterministic clock stays in lockstep with
            // processing — preserving backtest==live. No-op for SystemClock.
            (clock as? com.qkt.common.MutableClock)?.advanceTo(msg.tick.timestamp)
            pipeline.ingest(msg.tick)
        } catch (e: Exception) {
            onEngineFault("tick ${msg.tick.symbol}@${msg.tick.timestamp}", e)
        } finally {
            if (pipeline.latency.enabled) {
                pipeline.latency.observeAll(
                    com.qkt.observability.LatencyStage.TICK_PROCESSING,
                    System.nanoTime() - latencyStartNanos,
                )
            }
        }
    }

    private fun run() {
        if (mdcStrategy != null) org.slf4j.MDC.put("strategy", mdcStrategy)
        try {
            var stopDeadlineNanos: Long? = null
            while (running.get()) {
                val msg: Inbound? =
                    control.poll()
                        ?: if (stopDeadlineNanos == null) {
                            tickQueue.poll(QUEUE_POLL_MS, TimeUnit.MILLISECONDS)
                        } else {
                            control.poll(QUEUE_POLL_MS, TimeUnit.MILLISECONDS)
                        }
                if (msg == null) {
                    val deadline = stopDeadlineNanos
                    if (deadline != null && System.nanoTime() >= deadline && control.isEmpty()) {
                        running.set(false)
                    }
                    continue
                }
                when (msg) {
                    is Inbound.FeedTick -> processTick(msg)
                    is Inbound.BusEvent ->
                        try {
                            bus.publish(msg.event)
                        } catch (e: Exception) {
                            onEngineFault("event ${msg.event::class.simpleName}", e)
                        }
                    is Inbound.Heartbeat ->
                        runCatching {
                            // Control drains ahead of ticks, so a heartbeat can overtake
                            // ticks that were queued before it fired. Those ticks precede
                            // the heartbeat in event time: process them first, or the
                            // wall-clock close rejects them as late (#1058).
                            while (true) processTick(tickQueue.poll() ?: break)
                            for (symbol in feedSymbols) marketDataGate.isHealthy(symbol)
                            pipeline.scheduleHeartbeat(msg.nowMs, candleCloseGraceMs)
                        }.onFailure { t -> onEngineFault("schedule heartbeat", t) }
                    Inbound.PersistenceHealthCheck -> persistenceWatch.checkPersistenceHealth()
                    is Inbound.Query -> msg.execute()
                    Inbound.Flatten ->
                        // A failed FLATTEN is the emergency path failing — the loudest case.
                        // Then the venue's own list: a resting order whose placement response was
                        // lost is not among the orders the engine knows, and must not outlive a flatten.
                        runCatching { flatten.flattenAndSweep() }
                            .onFailure { t -> onEngineFault("flatten", t) }
                    is Inbound.FeedEnded -> {
                        // Feed ended (finite source drained): process every tick already
                        // queued before stopping, so no tick is dropped.
                        if (!stopping.get()) {
                            while (true) processTick(tickQueue.poll() ?: break)
                            if (msg.unexpected) sessionNotifier.unexpectedFeedEnd(msg.reason)
                            running.set(false)
                        }
                    }
                    is Inbound.GracefulStop -> stopDeadlineNanos = msg.deadlineNanos
                }
                val deadline = stopDeadlineNanos
                if (deadline != null && System.nanoTime() >= deadline && control.isEmpty()) {
                    running.set(false)
                }
            }
        } catch (e: InterruptedException) {
            log.info("LiveSession engine thread interrupted")
            Thread.currentThread().interrupt()
        } finally {
            running.set(false)
            // After the final drain, so the stop flatten's fills are already booked and no
            // later bar can fire on the cleared edges before the session is gone.
            if (clearRuleEdgesAtStop.get()) {
                for ((strategyId, strategy) in strategies) {
                    if (strategy !is DslCompiledStrategy) continue
                    runCatching { strategy.clearRuleEdges() }
                        .onFailure { t -> log.warn("could not clear rule edges for {} at stop", strategyId, t) }
                }
            }
            // Journal appends run on this thread (bus dispatch), so its channels
            // close here — the last event is already durable when we count down.
            runCatching { journal?.close() }
            runCatching { auditJournal?.close() }
            terminated.countDown()
            if (mdcStrategy != null) org.slf4j.MDC.remove("strategy")
        }
    }
}
