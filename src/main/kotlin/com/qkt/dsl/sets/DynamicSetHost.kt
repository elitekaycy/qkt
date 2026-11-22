package com.qkt.dsl.sets

import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.CompiledStrategy
import com.qkt.dsl.compile.DecisionOrderLink
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.ExitRetry
import com.qkt.dsl.compile.HubKey
import com.qkt.events.Event
import com.qkt.events.SetMembershipChanged
import com.qkt.instrument.universe.MarketFacts
import com.qkt.instrument.universe.MembershipChange.UNAVAILABLE
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.strategy.PerStreamWarmable
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.WarmupStream

/**
 * A strategy whose `DYNAMIC` symbol sets gain and lose members during the run (runtime membership plan, step 4):
 * the strategy's own rules ([outer], compiled with every candidate member's stream declared, so the run feeds and
 * keeps history for each) and, per set, the member instances of [SetMemberInstances].
 *
 * At the first tick at or past each close of a set's window, before that tick reaches the candle hub, the set's
 * [DynamicSetMembership] steps with the members that hold a position or a working entry order; the instances apply
 * the step, a joining member firing its rules from the next close on, and every change is published as a
 * [SetMembershipChanged] (and handed to [journal]). A joining member warms from the history the hub already holds
 * for its stream, and when that is short from [warmup] over the session's market source (older bars only). With
 * [seedCandidates] the run also seeds every candidate's history at start, as a backtest's declared streams are; a live
 * session leaves that to the joins, so a candidate with short history never fails its start. A backtest and a live
 * session drive it through the same tick path, so membership, warmup and trades replay alike. Order callbacks reach
 * whichever rule set sent the order.
 */
internal class DynamicSetHost(
    private val outer: CompiledStrategy,
    private val sets: List<HostedSet>,
    private val warmup: (MarketSource) -> (HubKey, Int, Long) -> List<Candle> = { { _, _, _ -> emptyList() } },
    private val seedCandidates: Boolean = true,
    private val journal: (SetMembershipChanged) -> Unit = {},
) : DslCompiledStrategy by outer,
    PerStreamWarmable {
    private val changes = MembershipJournal(journal)
    private var running: List<Pair<HostedSet, SetMemberInstances>> = emptyList()

    /** Every candidate member's stream, with what a member instance asks of it. */
    private val candidates: Map<HubKey, SetMemberTemplate.StreamNeeds> =
        sets
            .flatMap { set ->
                val keys = keysOf(set)
                val needs = keys.firstOrNull()?.let { set.template.streamNeeds(it.symbol) }
                keys.mapNotNull { key -> needs?.let { key to it } }
            }.toMap()

    /** The watched tier of each set that reads market fields: a record per window for every symbol of its venue. */
    private val watched: Map<HostedSet, WatchedWindows> =
        sets
            .filter { it.membership.readsMarket }
            .associateWith { set ->
                WatchedWindows(set.membership.windowMs) { it.substringBefore(':').equals(set.venue, true) }
            }

    override val retentionByKey: Map<HubKey, Int> =
        outer.retentionByKey +
            candidates.mapValues { (key, needs) -> maxOf(needs.retention, outer.retentionByKey[key] ?: 1) }

    override val perStreamWarmup: Map<WarmupStream, WarmupSpec> =
        outer.perStreamWarmup +
            candidates.entries
                .filter { seedCandidates }
                .mapNotNull { (key, needs) -> needs.warmup?.let { stream(key) to it } }
                .toMap()

    /** The sets this strategy runs, for a live session that keeps their rows to what it can feed. */
    val hosted: List<HostedSet> get() = sets

    /** Wraps the session's market source when the strategy needs more of it (a live set's subscriptions); or none. */
    var sourceDecorator: ((MarketSource) -> MarketSource)? = null

    /** Publish each membership change through [publish] (the pipeline's bus) from now on. */
    fun journalTo(publish: (Event) -> Unit) {
        changes.publish = publish
    }

    /** The candidate members (bare symbols) of [set] the strategy declared at start. */
    fun declaredMembers(set: String): Set<String> =
        keysOf(sets.first { it.template.set == set }).mapTo(HashSet()) { it.symbol }

    /**
     * Journals that [members] of [set] could not be subscribed at [atMs], so they never join: safe from another
     * thread, published at the next tick with the run's other membership changes.
     */
    fun unavailable(
        set: String,
        members: Collection<String>,
        atMs: Long,
    ) {
        val venue = sets.first { it.template.set == set }.venue
        members.forEach { changes.later(SetMembershipChanged("", set, "$venue:$it", UNAVAILABLE, null, atMs)) }
    }

    override fun bindToHub(
        hub: CandleHub,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        outer.bindToHub(hub, ctx, emit)
        val history = warmup(ctx.source)
        running = sets.map { it to SetMemberInstances(it.template, hub, ctx, emit, it::ladder, history) }
    }

    override fun onTick(
        tick: Tick,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        outer.onTick(tick, ctx, emit)
        changes.drain(ctx.strategyId)
        for ((set, instances) in running) {
            val watch = watched[set]
            set.membership
                .at(tick.timestamp, { watch?.asOf(it) ?: MarketFacts.NONE }) { held(set.venue, instances, ctx) }
                ?.let { apply(set, instances, it, ctx) }
            watch?.onTick(tick)
        }
        for ((_, instances) in running) instances.compiled.forEach { it.onTick(tick, ctx, emit) }
    }

    override fun onOrderSubmitted(
        signal: Signal,
        clientOrderId: String,
    ): DecisionOrderLink? = all().firstNotNullOfOrNull { it.onOrderSubmitted(signal, clientOrderId) }

    override fun onOrderRejected(clientOrderId: String) = all().forEach { it.onOrderRejected(clientOrderId) }

    override fun onOrderFilled(clientOrderId: String) = all().forEach { it.onOrderFilled(clientOrderId) }

    override fun onOrderTerminal(clientOrderId: String) = all().forEach { it.onOrderTerminal(clientOrderId) }

    override fun onExitOrderUnfilled(clientOrderId: String): ExitRetry? =
        all().firstNotNullOfOrNull { it.onExitOrderUnfilled(clientOrderId) }

    override fun onPositionStateChanged(
        symbol: String,
        nowHeld: Boolean,
        atMs: Long,
    ) = all().forEach { it.onPositionStateChanged(symbol, nowHeld, atMs) }

    override fun clearRuleEdges() = all().forEach { it.clearRuleEdges() }

    /** Applies [evaluation] to [instances] and journals each change under the member's qkt symbol. */
    private fun apply(
        set: HostedSet,
        instances: SetMemberInstances,
        evaluation: DynamicSetMembership.Evaluation,
        ctx: StrategyContext,
    ) {
        instances.apply(evaluation.step, evaluation.closeMs)
        for (change in SetMembershipChanged.of(ctx.strategyId, set.template.set, evaluation.closeMs, evaluation.step)) {
            changes.record(change.copy(member = "${set.venue}:${change.member}"))
        }
    }

    private fun keysOf(set: HostedSet): Collection<HubKey> =
        outer.declaredStreams.filterKeys { SetMemberAlias.setOf(it) == set.template.set }.values

    /** The strategy's own rules first, then every running member instance. */
    private fun all(): Sequence<CompiledStrategy> =
        sequenceOf(outer) + running.asSequence().flatMap { (_, instances) -> instances.compiled }

    /** The running members of a set that hold a position or a working entry order. */
    private fun held(
        venue: String,
        instances: SetMemberInstances,
        ctx: StrategyContext,
    ): Set<String> =
        instances.members.filterTo(HashSet()) { member ->
            val symbol = "$venue:$member"
            val position = ctx.positions.positionFor(symbol)
            (position?.quantity?.signum() ?: 0) != 0 ||
                ctx.openOrders.entryCountFor(symbol) > 0 ||
                instances.holdsThroughSlot(member, ctx.clock.now())
        }

    private fun stream(key: HubKey): WarmupStream = WarmupStream(key.qktSymbol, TimeWindow.parse(key.timeframe))
}
