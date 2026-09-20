package com.qkt.cli.daemon.routes

import com.qkt.cli.daemon.AutoDeployRetrier
import com.qkt.cli.daemon.StrategyRegistry
import com.sun.net.httpserver.HttpExchange
import java.time.Instant
import kotlinx.serialization.builtins.serializer

/**
 * `GET /health` — daemon liveness plus per-strategy last-event age, halt state and
 * queue depth; `degraded` while a `--load-dir` auto-deploy is still pending.
 */
internal fun handleHealth(
    ex: HttpExchange,
    registry: StrategyRegistry,
    startedAt: Instant,
    pendingAutoDeploys: List<AutoDeployRetrier.Pending> = emptyList(),
) {
    val now = Instant.now().toEpochMilli()
    val uptimeMs = now - startedAt.toEpochMilli()
    val handles = registry.list()
    // Per-strategy last-event age lets an external watchdog tell a WEDGED single
    // session (dead engine thread, growing queue, no events) from a healthy idle
    // one — the daemon answering /health alone cannot (#397).
    val perStrategy =
        handles.joinToString(",", "[", "]") { h ->
            val lastEvent =
                h.ring
                    .snapshot(0, 1_000)
                    .lastOrNull()
                    ?.ts
            val ageMs = lastEvent?.let { now - it }
            val haltReason = h.live.haltReason()
            """{"name":"${h.name}","running":${h.isRunning()},""" +
                """"halted":${h.live.isHalted()},""" +
                """"haltReason":${haltReason?.let { routeJson.encodeToString(String.serializer(), it) } ?: "null"},""" +
                """"lastEventAgeMs":${ageMs ?: "null"},""" +
                """"inboundQueueDepth":${h.live.inboundQueueDepth()},""" +
                """"droppedTicks":${h.live.droppedTicks}}"""
        }
    // A `--load-dir` file still waiting to deploy (#1055) means the daemon is idle where an
    // operator expects a strategy: degraded, not ok, so watchdogs and Insights see it.
    val pending =
        pendingAutoDeploys.joinToString(",", "[", "]") { p ->
            """{"name":${routeJson.encodeToString(String.serializer(), p.name)},""" +
                """"attempts":${p.attempts},"nextAttemptAtMs":${p.nextAttemptAtMs},""" +
                """"lastError":${routeJson.encodeToString(String.serializer(), p.lastError)}}"""
        }
    val status = if (pendingAutoDeploys.isEmpty()) "ok" else "degraded"
    respond(
        ex,
        200,
        """{"status":"$status","strategies":${handles.size},"uptimeMs":$uptimeMs,""" +
            """"pendingAutoDeploys":$pending,"perStrategy":$perStrategy}""",
    )
}
