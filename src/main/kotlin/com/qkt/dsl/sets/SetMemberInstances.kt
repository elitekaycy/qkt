package com.qkt.dsl.sets

import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.CompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.instrument.universe.MembershipChange
import com.qkt.instrument.universe.MembershipStep
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext

/**
 * The live instances of one symbol set's members (runtime membership plan, step 3), driven by the membership steps
 * of [com.qkt.instrument.universe.SetMembership]. Members are the venue's bare symbols.
 *
 * - A member that joins (or is restored) gets its own compiled instance ([SetMemberTemplate]), registered on [hub]
 *   under its own owner id so it can be dropped alone. Its stream is warmed with the closed bars its warmup asks for:
 *   what the hub holds, and when that is short the older bars [warmup] (key, bar count, latest bar end) reads, then
 *   replayed through its indicators exactly as a declared stream's warmup is. It evaluates from the next close on,
 *   never at the close it joined.
 * - A joining member's order ids continue past every id it sent before: the ids it sent before it last left, and
 *   what a restarted session restored ([restore]: active orders' ids and every generator's persisted mark), so no id
 *   repeats, which a venue's idempotency on the client order id would otherwise swallow.
 * - A member's rule edges persist under its own id ([MemberRestore.persistor]), so a restart over a member ranked
 *   again does not fire an edge it already fired; a member that leaves clears them, so a rejoin starts fresh as in a
 *   backtest.
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
    private val restore: MemberRestore = MemberRestore(),
    private val ladder: () -> StrikeLadder? = { null },
    private val warmup: (HubKey, Int, Long) -> List<Candle>,
) {
    private val retiredMarks = HashMap<String, Long>()
    private val instances = LinkedHashMap<String, CompiledStrategy>()
    private val sticky = HashSet<String>()
    private val sections =
        SetCrossSections({ alias, step, atMs ->
            val symbol = instances.keys.firstOrNull { template.alias(it) == alias }
            symbol?.let { ladder()?.neighbour(it, step, atMs) }?.takeIf { it in instances }?.let(template::alias)
        }) { instances.keys.filterNot { it in sticky }.map(template::alias) }
    private var refused = 0L
    private val warming = MemberWarmup(hub, warmup)

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

    /** The running members (bare symbols of [venue]) that hold a position, a working entry order or a slot contract. */
    fun held(venue: String): Set<String> =
        instances.keys.filterTo(HashSet()) { member ->
            val symbol = "$venue:$member"
            val position = ctx.positions.positionFor(symbol)
            (position?.quantity?.signum() ?: 0) != 0 ||
                ctx.openOrders.entryCountFor(symbol) > 0 ||
                holdsThroughSlot(member, ctx.clock.now())
        }

    /** Each member generator's last issued sequence, by prefix: the running members' and those that left. */
    fun idMarks(): Map<String, Long> =
        retiredMarks +
            instances.values.mapNotNull { i -> i.orderIds?.let { g -> g.lastIssued()?.let { g.prefix to it } } }

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
        warming.stepped()
    }

    private fun join(
        member: String,
        closeMs: Long,
    ) {
        val instance = template.compile(member, sections)
        instance.orderIds?.let { ids ->
            listOfNotNull(retiredMarks[ids.prefix], restore.marks[ids.prefix]).maxOrNull()?.let(ids::resumeAfter)
        }
        instance.resumeOrderIds(restore.usedIds)
        restore.persistor?.let { instance.bindStatePersistor(stateId(member), it) }
        if (restore.clearEdges) instance.clearRuleEdges()
        instance.evaluateAfter(closeMs)
        val owner = owner(member)
        val key = instance.declaredStreams.getValue(template.alias(member))
        warming.warm(instance, key, owner, closeMs)
        instance.bindToHubAs(owner, hub, ctx) { signal ->
            if (passes(member, signal)) emit(signal) else refuse(instance, owner, signal)
        }
        instances[member] = instance
    }

    private fun leave(member: String) {
        instances[member]?.orderIds?.let { ids -> ids.lastIssued()?.let { retiredMarks[ids.prefix] = it } }
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
