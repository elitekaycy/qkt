package com.qkt.cli.daemon

import com.qkt.common.Clock
import com.qkt.dsl.sets.DynamicSetHost
import com.qkt.dsl.sets.DynamicSetMembership
import com.qkt.dsl.sets.SetCatalog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Keeps a live session's moving sets to what it can feed (runtime membership plan, step 8). Each [poll] reads every
 * set's venue listing from [catalog] again, works out the candidates any close of the next [horizonMs] picks, and
 * [subscribe]s the ones the session does not serve yet. Only rows whose market data is served are handed to the set's
 * membership, so a member is never picked that the session cannot feed; a candidate whose subscription fails never
 * joins, and is logged and journalled ([DynamicSetHost.unavailable]) at that poll.
 */
internal class LiveSetPoller(
    private val host: DynamicSetHost,
    private val catalog: SetCatalog,
    private val subscribe: (Collection<String>) -> Set<String>,
    private val horizonMs: Long = LIVE_CANDIDATE_HORIZON_MS,
) {
    private val log = LoggerFactory.getLogger(LiveSetPoller::class.java)
    private val served: Map<String, MutableSet<String>> =
        host.hosted.associate { it.template.set to host.declaredMembers(it.template.set).toMutableSet() }

    /** One poll of every set's listing as of [nowMs]. */
    @Synchronized
    fun poll(nowMs: Long) {
        for (set in host.hosted) {
            val alias = set.template.set
            val spec = set.membership.spec
            val rows =
                runCatching { catalog.venue(alias, spec).rows }
                    .onFailure { log.warn("symbol set '{}': listing not read, members kept: {}", alias, it.message) }
                    .getOrNull() ?: continue
            val fed = served.getValue(alias)
            val windowMs = set.membership.windowMs
            val wanted = DynamicSetMembership.candidatesOver(spec, rows, windowMs, nowMs, nowMs + horizonMs)
            val fresh = wanted.filter { it !in fed }
            if (fresh.isNotEmpty()) {
                val now = subscribe(fresh.map { "${spec.venue}:$it" }).mapTo(HashSet()) { it.substringAfter(':') }
                fed += now
                val failed = fresh.filter { it !in now }
                if (failed.isNotEmpty()) {
                    log.warn("symbol set '{}': cannot subscribe {}; they do not join", alias, failed)
                    host.unavailable(alias, failed, nowMs)
                }
            }
            set.membership.list(rows.filter { it.qktSymbol.substringAfter(':') in fed })
        }
    }

    /** Polls every [intervalMs] of [clock] on a daemon thread until the returned handle is closed. */
    fun start(
        intervalMs: Long,
        clock: Clock,
    ): AutoCloseable {
        val runner =
            Executors.newSingleThreadScheduledExecutor { Thread(it, "live-set-poller").apply { isDaemon = true } }
        val tick =
            Runnable { runCatching { poll(clock.now()) }.onFailure { log.warn("set poll failed: {}", it.message) } }
        runner.scheduleWithFixedDelay(tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
        return AutoCloseable { runner.shutdownNow() }
    }
}
