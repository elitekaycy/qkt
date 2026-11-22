package com.qkt.dsl.sets

import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.CompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.WarmupStream

/**
 * The candidate member streams of a [DynamicSetHost]'s [sets], as [outer] (the strategy's own rules) declares them:
 * what the hub keeps of each (a member instance's retention or warmup, the larger), the warmup a run seeds for each
 * at start when [seedCandidates], and the watched tier of each set that reads market fields.
 */
internal class CandidateStreams(
    private val outer: CompiledStrategy,
    private val sets: List<HostedSet>,
    seedCandidates: Boolean,
) {
    /** Every candidate member's stream, with what a member instance asks of it. */
    private val needs: Map<HubKey, SetMemberTemplate.StreamNeeds> =
        sets
            .flatMap { set ->
                val keys = keysOf(set)
                val needs = keys.firstOrNull()?.let { set.template.streamNeeds(it.symbol) }
                keys.mapNotNull { key -> needs?.let { key to it } }
            }.toMap()

    /** The watched tier of each set that reads market fields: a record per window for every symbol of its venue. */
    val watched: Map<HostedSet, WatchedWindows> =
        sets
            .filter { it.membership.readsMarket }
            .associateWith { set ->
                WatchedWindows(set.membership.windowMs) { it.substringBefore(':').equals(set.venue, true) }
            }

    val retentionByKey: Map<HubKey, Int> =
        outer.retentionByKey +
            needs.mapValues { (key, need) -> maxOf(need.retention, outer.retentionByKey[key] ?: 1) }

    val perStreamWarmup: Map<WarmupStream, WarmupSpec> =
        outer.perStreamWarmup +
            needs.entries
                .filter { seedCandidates }
                .mapNotNull { (key, need) -> need.warmup?.let { stream(key) to it } }
                .toMap()

    /**
     * One compiled member of each set that declares a candidate. Every member of a set runs the same rules on the
     * same venue, so what one asks of the session (volume, marks, a book, stacked legs) every member asks: the
     * session's start checks read these beside [outer], which holds none of the member rules.
     */
    private val samples: List<CompiledStrategy> =
        sets.mapNotNull { set -> keysOf(set).firstOrNull()?.let { set.template.compile(it.symbol) } }

    val volumeRequiringSymbols: Set<String> =
        samples.fold(outer.volumeRequiringSymbols) { all, member -> all + member.volumeRequiringSymbols }

    val markSymbols: Set<String> = samples.fold(outer.markSymbols) { all, member -> all + member.markSymbols }

    val usesBookSizing: Boolean = outer.usesBookSizing || samples.any { it.usesBookSizing }

    /**
     * What a member rule uses that a live session cannot run for a moving set yet, or null: stacked entries
     * (`STACK_AT`) and exit hooks are tracked per strategy, and a member's would never be acted on.
     */
    fun unsupportedLive(): String? =
        when {
            samples.any { it.multiPositionPerSymbolSymbols.isNotEmpty() } -> "STACK_AT"
            samples.any { it.exitHookReferences().isNotEmpty() } -> "an exit hook"
            else -> null
        }

    /** The candidate members (bare symbols) of the set named [set] that [outer] declares. */
    fun declaredMembers(set: String): Set<String> =
        keysOf(sets.first { it.template.set == set }).mapTo(HashSet()) { it.symbol }

    private fun keysOf(set: HostedSet): Collection<HubKey> =
        outer.declaredStreams.filterKeys { SetMemberAlias.setOf(it) == set.template.set }.values

    private fun stream(key: HubKey): WarmupStream = WarmupStream(key.qktSymbol, TimeWindow.parse(key.timeframe))
}
