package com.qkt.dsl.sets

import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.CompiledStrategy
import com.qkt.dsl.compile.DecisionOrderLink
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.ExitRetry
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.OrderIdMarks
import com.qkt.events.Event
import com.qkt.events.SetMembershipChanged
import com.qkt.instrument.universe.MarketFacts
import com.qkt.instrument.universe.MembershipChange.UNAVAILABLE
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.persistence.StatePersistor
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
    seedCandidates: Boolean,
    private val journal: (SetMembershipChanged) -> Unit = {},
) : DslCompiledStrategy by outer,
    PerStreamWarmable,
    OrderIdMarks {
    private val changes = MembershipJournal(journal)
    private val started = HashSet<HostedSet>()
    private val restore = MemberRestore()
    private var running: List<Pair<HostedSet, SetMemberInstances>> = emptyList()

    private val streams = CandidateStreams(outer, sets, seedCandidates)

    override val retentionByKey: Map<HubKey, Int> get() = streams.retentionByKey

    override val perStreamWarmup: Map<WarmupStream, WarmupSpec> get() = streams.perStreamWarmup

    /** The sets this strategy runs, for a live session that keeps their rows to what it can feed. */
    val hosted: List<HostedSet> get() = sets

    /** Wraps the session's market source when the strategy needs more of it (a live set's subscriptions); or none. */
    var sourceDecorator: ((MarketSource) -> MarketSource)? = null

    /** Publish each membership change through [publish] (the pipeline's bus) from now on. */
    fun journalTo(publish: (Event) -> Unit) {
        changes.publish = publish
    }

    /** The candidate members (bare symbols) of [set] the strategy declared at start. */
    fun declaredMembers(set: String): Set<String> = streams.declaredMembers(set)

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
        running = sets.map { it to SetMemberInstances(it.template, hub, ctx, emit, restore, it::ladder, history) }
    }

    override fun onTick(
        tick: Tick,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        outer.onTick(tick, ctx, emit)
        changes.drain(ctx.strategyId)
        for ((set, instances) in running) {
            val watch = streams.watched[set]
            val atStart = if (started.add(set)) heldAtStart(set, ctx) else emptySet()
            set.membership
                .at(tick.timestamp, { watch?.asOf(it) ?: MarketFacts.NONE }, atStart.isNotEmpty()) {
                    atStart.ifEmpty { instances.held(set.venue) }
                }?.let { apply(set, instances, it, ctx) }
            watch?.onTick(tick)
        }
        for ((_, instances) in running) instances.compiled.forEach { it.onTick(tick, ctx, emit) }
    }

    override fun bindStatePersistor(
        strategyId: String,
        persistor: StatePersistor,
    ) {
        restore.persistor = persistor
        outer.bindStatePersistor(strategyId, persistor)
    }

    override fun resumeOrderIds(usedIds: Collection<String>) {
        restore.usedIds = usedIds
        outer.resumeOrderIds(usedIds)
    }

    override fun resumeOrderIdMarks(marks: Map<String, Long>) {
        restore.marks = marks
    }

    override fun orderIdMarks(): Map<String, Long> =
        running.fold(emptyMap()) { marks, (_, instances) -> marks + instances.idMarks() }

    /** The declared members of [set] the strategy holds when it starts: a restart's positions, restored sticky. */
    private fun heldAtStart(
        set: HostedSet,
        ctx: StrategyContext,
    ): Set<String> =
        declaredMembers(set.template.set).filterTo(HashSet()) {
            val position = ctx.positions.positionFor("${set.venue}:$it")
            (position?.quantity?.signum() ?: 0) != 0
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

    /** The strategy's own rules first, then every running member instance. */
    private fun all(): Sequence<CompiledStrategy> =
        sequenceOf(outer) + running.asSequence().flatMap { (_, instances) -> instances.compiled }
}
