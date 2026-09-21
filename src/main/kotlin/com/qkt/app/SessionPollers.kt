package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.risk.RiskState
import java.math.BigDecimal
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicReference

/**
 * Starts the timer threads that run beside a live session's engine loop. Neither touches engine
 * state: the heartbeat only posts onto the [EngineMailbox], and the equity poller only fills a
 * holder the engine reads, e.g. with a 1000ms interval the loop sees one `Heartbeat` a second
 * even when no tick arrives.
 */
internal class SessionPollers(
    private val mailbox: EngineMailbox,
    private val riskState: RiskState,
    private val clock: Clock,
    private val broker: Broker,
    private val bus: EventBus,
) {
    private val control = mailbox.control

    /**
     * Quiet-market heartbeat (#77 Phase 40 follow-up). Without this, SCHEDULE fires only happen
     * when ticks arrive — a 19:55 UTC placement would slip by seconds on a quiet Asia session.
     * It posts onto the queue so scheduleRunner.tick only ever runs on the engine thread.
     */
    fun startScheduleHeartbeat(scheduleHeartbeatIntervalMs: Long): ScheduledExecutorService {
        val scheduleHeartbeat: java.util.concurrent.ScheduledExecutorService =
            java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor { r ->
                    Thread(r, "qkt-schedule-heartbeat").apply { isDaemon = true }
                }
        scheduleHeartbeat.scheduleAtFixedRate(
            {
                runCatching { riskState.persistAnchorsIfDirty() }
                runCatching { control.put(Inbound.PersistenceHealthCheck) }
                runCatching { control.put(Inbound.Heartbeat(clock.now())) }
            },
            scheduleHeartbeatIntervalMs,
            scheduleHeartbeatIntervalMs,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        return scheduleHeartbeat
    }

    /**
     * #352: poll real account equity off the engine thread so sizing + drawdown track the
     * broker's account (commissions, swaps, deposits), not just engine-derived PnL. Standalone
     * single-strategy only ([standaloneVenueEquity]) — allocated portfolio children do not own
     * the whole account. Capability is static: a
     * transiently failed startup read must not disable polling for the entire session. Failed
     * reads retain the last-known value and alert once stale. The network call stays off the consumer.
     */
    fun startEquityPoller(
        standaloneVenueEquity: Boolean,
        brokerEquity: AtomicReference<BigDecimal?>,
        brokerEquityStaleMs: Long,
        brokerEquityPollMs: Long,
    ): ScheduledExecutorService? =
        if (standaloneVenueEquity &&
            broker.supportsAccountEquity
        ) {
            val monitor =
                BrokerEquityMonitor(
                    broker = broker,
                    clock = clock,
                    equity = brokerEquity,
                    staleAfterMs = brokerEquityStaleMs,
                    onStale = { failures, staleForMs ->
                        bus.publish(
                            BrokerEvent.AccountEquityStale(
                                broker = broker.name,
                                consecutiveFailures = failures,
                                staleForMs = staleForMs,
                                timestamp = clock.now(),
                            ),
                        )
                    },
                )
            java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor { r ->
                    Thread(r, "qkt-broker-equity-poller").apply { isDaemon = true }
                }.also { exec ->
                    exec.scheduleAtFixedRate(
                        monitor::tick,
                        0L,
                        brokerEquityPollMs,
                        java.util.concurrent.TimeUnit.MILLISECONDS,
                    )
                }
        } else {
            null
        }
}
