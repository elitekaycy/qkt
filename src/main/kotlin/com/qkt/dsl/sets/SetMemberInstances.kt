package com.qkt.dsl.sets

import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.CompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.instrument.universe.MembershipChange
import com.qkt.instrument.universe.MembershipStep
import com.qkt.marketdata.Candle
import com.qkt.persistence.StatePersistor
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.WarmupStream

/**
 * The live instances of one symbol set's members (runtime membership plan, step 3), driven by the membership steps
 * of [com.qkt.instrument.universe.SetMembership]. Members are the venue's bare symbols.
 *
 * - A member that joins (or is restored) gets its own compiled instance ([SetMemberTemplate]), registered on [hub]
 *   under its own owner id so it can be dropped alone. Its stream is warmed with the closed bars its warmup asks for:
 *   what the hub holds, and when that is short the older bars [warmup] (key, bar count, latest bar end) reads, then
 *   replayed through its indicators exactly as a declared stream's warmup is. It evaluates from the next close on,
 *   never at the close it joined.
 * - A joining member's order ids continue past [resumedIds] (the ids a restarted session restored), so a member that
 *   traded before a restart never sends an id it already sent.
 * - A member's rule edges persist under its own id ([persistor]), so a restart over a member ranked again does not
 *   fire an edge it already fired; a member that leaves clears them, so a rejoin starts fresh as in a backtest.
 * - A member that leaves or is released is unregistered and its instance and state are dropped; joining again starts
 *   from a fresh warmup.
 * - Cross-member functions over the set read the current members' values ([SetCrossSections]), sticky ones left out;
 *   `NEXT_STRIKE` / `PREV_STRIKE` read the [ladder] neighbour's, when it is an active member (sticky included).
 * - A sticky member (it holds but left the ranking) keeps running, but an order that would open or add on it is
 *   refused, re-arming the rule that sent it as a rejection does: only reducing and closing orders pass.
 */
internal class SetMemberInstances(
    private val template: SetMemberTemplate,
    private val hub: CandleHub,
    private val ctx: StrategyContext,
    private val emit: (Signal) -> Unit,
    private val resumedIds: () -> Collection<String> = { emptyList() },
    private val persistor: StatePersistor? = null,
    private val ladder: () -> StrikeLadder? = { null },
    private val warmup: (HubKey, Int, Long) -> List<Candle>,
) {
    private val instances = LinkedHashMap<String, CompiledStrategy>()
    private val sticky = HashSet<String>()
    private val sections =
        SetCrossSections({ alias, step, atMs ->
            val symbol = instances.keys.firstOrNull { template.alias(it) == alias }
            symbol?.let { ladder()?.neighbour(it, step, atMs) }?.takeIf { it in instances }?.let(template::alias)
        }) { instances.keys.filterNot { it in sticky }.map(template::alias) }
    private var refused = 0L

    /** The members with a running instance, in the order they joined. */
    val members: Set<String> get() = instances.keys

    /** The running instances, in the order their members joined. */
    val compiled: Collection<CompiledStrategy> get() = instances.values

    /**
     * Whether [member]'s instance holds a contract through an event slot it trades ([CompiledStrategy.slotBooks]): a
     * slot universe's member holds the contracts its slot opened, never the slot symbol itself.
     */
    fun holdsThroughSlot(
        member: String,
        nowMs: Long,
    ): Boolean {
        val books = instances[member]?.slotBooks ?: return false
        return books.total(ctx, template.alias(member), nowMs).signum() != 0
    }

    /** Whether [member] is sticky: held while out of the ranking. */
    fun isSticky(member: String): Boolean = member in sticky

    /**
     * Applies every change of [step], in its order. [closeMs] is the window close the step is as of: a member that
     * joins there fires its rules from the next close on, whenever the close itself reaches the hub.
     */
    fun apply(
        step: MembershipStep,
        closeMs: Long = Long.MIN_VALUE,
    ) {
        for (change in step.changes) {
            when (change.change) {
                MembershipChange.JOIN -> join(change.member, closeMs)
                MembershipChange.RESTORE -> {
                    join(change.member, closeMs)
                    if (change.rank == null) sticky += change.member
                }
                MembershipChange.LEAVE, MembershipChange.RELEASE -> leave(change.member)
                MembershipChange.STICK -> sticky += change.member
                MembershipChange.STAY -> sticky -= change.member
                MembershipChange.UNAVAILABLE -> Unit
            }
        }
    }

    private fun join(
        member: String,
        closeMs: Long,
    ) {
        val instance = template.compile(member, sections)
        instance.resumeOrderIds(resumedIds())
        persistor?.let { instance.bindStatePersistor(stateId(member), it) }
        instance.evaluateAfter(closeMs)
        val owner = owner(member)
        val key = instance.declaredStreams.getValue(template.alias(member))
        val stream = WarmupStream(key.qktSymbol, TimeWindow.parse(key.timeframe))
        val need = (instance.perStreamWarmup[stream] as? WarmupSpec.Bars)?.count ?: 0
        for (k in instance.declaredStreams.values.toSet() + instance.retentionByKey.keys) {
            val retention = instance.retentionByKey[k] ?: 1
            hub.register(k, if (k == key) maxOf(retention, need) else retention, owner)
        }
        val held = hub.seededHistory(key)
        if (need > held.size) {
            val bars = warmup(key, need - held.size, warmupEndMs(held, key, closeMs))
            if (bars.isNotEmpty()) hub.seed(key, bars)
        }
        instance.bindToHubAs(owner, hub, ctx) { signal ->
            if (passes(member, signal)) emit(signal) else refuse(instance, owner, signal)
        }
        instances[member] = instance
    }

    /**
     * The latest bar end a joiner's warmup may read: the start of the oldest bar the hub holds for the stream, so
     * fetched history only ever goes in front of it; with none held, the start of the join close's own bar, which
     * reaches the hub as it closes (backtest and live alike, whichever comes first).
     */
    private fun warmupEndMs(
        held: List<Candle>,
        key: HubKey,
        closeMs: Long,
    ): Long =
        held.firstOrNull()?.startTime
            ?: if (closeMs == Long.MIN_VALUE) Long.MAX_VALUE else closeMs - TimeWindow.parse(key.timeframe).durationMs

    private fun leave(member: String) {
        instances[member]?.clearRuleEdges()
        hub.unregister(owner(member))
        instances.remove(member)
        sticky -= member
        sections.forget(template.alias(member))
    }

    private fun passes(
        member: String,
        signal: Signal,
    ): Boolean {
        if (member !in sticky) return true
        val (symbol, side) = signal.orderSide() ?: return true
        val position = ctx.positions.positionFor(symbol)
        return !opensOrAdds(side, position?.quantity?.signum() ?: 0)
    }

    /** A refused order re-arms the rule that sent it, as a rejected one does, so it fires again when it may. */
    private fun refuse(
        instance: CompiledStrategy,
        owner: String,
        signal: Signal,
    ) {
        val id = "$owner/refused/${++refused}"
        instance.onOrderSubmitted(signal, id)
        instance.onOrderRejected(id)
    }

    /** Where [member]'s rule edges persist: its own id under the strategy's, so a restart restores them. */
    private fun stateId(member: String): String = "${ctx.strategyId}.${template.set}.$member"

    private fun owner(member: String): String = "${ctx.strategyId}/${template.set}/$member"
}
