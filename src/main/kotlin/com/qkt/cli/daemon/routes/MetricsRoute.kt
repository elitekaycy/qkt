package com.qkt.cli.daemon.routes

import com.qkt.cli.daemon.PrometheusFormat
import com.qkt.cli.daemon.StrategyRegistry
import com.sun.net.httpserver.HttpExchange
import java.time.Instant

/**
 * `GET /metrics` — Prometheus text exposition format. Currently covers:
 * - Notifier counters (sent/dropped/failed/rateLimitHits) + degradedMode gauge.
 * - Daemon uptime + strategies-running gauge.
 * - Per-strategy trade counter.
 *
 * Latency exposition (per-strategy + per-stage from [com.qkt.observability.LatencyRegistry])
 * is deferred to a follow-up — needs design discussion on label cardinality and quantile
 * shape (summary vs histogram). See #79.
 */
internal fun handleMetrics(
    ex: HttpExchange,
    registry: StrategyRegistry,
    startedAt: Instant,
    notifierMetrics: com.qkt.notify.NotifierMetrics?,
) {
    val now = Instant.now()
    val out = PrometheusFormat()

    out.gauge(
        "qkt_daemon_uptime_seconds",
        "Seconds the daemon has been up",
        listOf(
            PrometheusFormat.Sample(
                value = ((now.toEpochMilli() - startedAt.toEpochMilli()) / 1_000L).toString(),
            ),
        ),
    )
    out.gauge(
        "qkt_strategies_running",
        "Count of strategies currently in running state",
        listOf(
            PrometheusFormat.Sample(
                value = registry.list().count { it.isRunning() }.toString(),
            ),
        ),
    )
    out.counter(
        "qkt_strategy_trades_total",
        "Total trades executed per strategy since daemon start",
        registry.list().map { h ->
            PrometheusFormat.Sample(
                labels = mapOf("strategy" to h.name),
                value = h.tradeCount.toString(),
            )
        },
    )

    if (notifierMetrics != null) {
        out.counter(
            "qkt_notifier_sent_total",
            "Notifications successfully sent",
            listOf(PrometheusFormat.Sample(value = notifierMetrics.sent.toString())),
        )
        out.counter(
            "qkt_notifier_dropped_total",
            "Notifications dropped without an attempt (queue full, etc)",
            listOf(PrometheusFormat.Sample(value = notifierMetrics.dropped.toString())),
        )
        out.counter(
            "qkt_notifier_failed_total",
            "Notification send attempts that failed",
            listOf(PrometheusFormat.Sample(value = notifierMetrics.failed.toString())),
        )
        out.counter(
            "qkt_notifier_rate_limit_hits_total",
            "Number of rate-limit responses observed from the notification provider",
            listOf(PrometheusFormat.Sample(value = notifierMetrics.rateLimitHits.toString())),
        )
        out.gauge(
            "qkt_notifier_degraded_mode",
            "1 when the notifier is in degraded mode (giving up retries), 0 otherwise",
            listOf(PrometheusFormat.Sample(value = if (notifierMetrics.degradedMode) "1" else "0")),
        )
    }

    respondText(ex, 200, out.toString())
}
