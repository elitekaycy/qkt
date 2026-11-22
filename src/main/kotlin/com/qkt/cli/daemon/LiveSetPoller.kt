package com.qkt.cli.daemon

import com.qkt.common.Clock
import com.qkt.dsl.sets.DynamicSetHost
import com.qkt.dsl.sets.DynamicSetMembership
import com.qkt.dsl.sets.HostedSet
import com.qkt.dsl.sets.SetCatalog
import com.qkt.instrument.InstrumentMeta
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Keeps a live session's moving sets to what it can feed (runtime membership plan, step 8). Each [poll] reads every
 * set's venue listing from [catalog] again, works out the candidates any close of the next [horizonMs] picks, and
 * [subscribe]s the ones the session does not serve yet. Only rows whose market data is served are handed to the set's
 * membership, so a member is never picked that the session cannot feed.
 *
 * - A candidate whose subscription fails never joins; it is logged and journalled ([DynamicSetHost.unavailable])
 *   once, at the poll that first fails, and tried again at every poll.
 * - A served contract that expired or left the listing, and that the set no longer has active, is let go
 *   ([unsubscribe]) so the session stops asking the venue for it.
 * - A listing that cannot be read, or that reads empty, changes nothing: the members are kept.
 */
internal class LiveSetPoller(
    private val host: DynamicSetHost,
    private val catalog: SetCatalog,
    private val subscribe: (Collection<String>) -> Set<String>,
    private val unsubscribe: (Collection<String>) -> Unit = {},
    private val horizonMs: Long = LIVE_CANDIDATE_HORIZON_MS,
) {
    private val log = LoggerFactory.getLogger(LiveSetPoller::class.java)
    private val served: Map<String, MutableSet<String>> =
        host.hosted.associate { it.template.set to host.declaredMembers(it.template.set).toMutableSet() }
    private val refused: Map<String, MutableSet<String>> = host.hosted.associate { it.template.set to HashSet() }

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
            if (rows.isEmpty()) {
                log.warn("symbol set '{}': listing read empty, members kept", alias)
                continue
            }
            val fed = served.getValue(alias)
            release(set, rows, fed, nowMs)
            val windowMs = set.membership.windowMs
            val wanted = DynamicSetMembership.candidatesOver(spec, rows, windowMs, nowMs, nowMs + horizonMs)
            val fresh = wanted.filter { it !in fed }
            if (fresh.isNotEmpty()) {
                val now = subscribe(fresh.map { "${spec.venue}:$it" }).mapTo(HashSet()) { it.substringAfter(':') }
                fed += now
                val failed = refused.getValue(alias)
                failed -= now
                val newly = fresh.filter { it !in now && failed.add(it) }
                if (newly.isNotEmpty()) {
                    log.warn("symbol set '{}': cannot subscribe {}; they do not join", alias, newly)
                    host.unavailable(alias, newly, nowMs)
                }
            }
            set.membership.list(rows.filter { it.qktSymbol.substringAfter(':') in fed })
        }
    }

    /** Lets go of every contract in [fed] that [rows] no longer lists unexpired and [set] no longer has active. */
    private fun release(
        set: HostedSet,
        rows: List<InstrumentMeta>,
        fed: MutableSet<String>,
        nowMs: Long,
    ) {
        val venue = set.membership.spec.venue
        val listed =
            rows
                .filterNot { DynamicSetMembership.expired(it, nowMs) }
                .mapTo(HashSet()) { it.qktSymbol.substringAfter(':') }
        val gone = fed.filter { it !in listed && "$venue:$it" !in set.membership.activeSymbols }
        if (gone.isEmpty()) return
        fed -= gone.toSet()
        unsubscribe(gone.map { "$venue:$it" })
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
