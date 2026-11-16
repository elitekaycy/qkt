package com.qkt.dsl.sets

import com.qkt.common.Side
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.CompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.execution.OrderRequest
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
 *   under its own owner id so it can be dropped alone. Its stream is warmed with up to its retention of closed bars
 *   from [warmup] (key, bar count) when the hub holds none, then replayed through its indicators exactly as a declared
 *   stream's warmup is. It evaluates from the next close on, never at the close it joined.
 * - A member that leaves or is released is unregistered and its instance and state are dropped; joining again starts
 *   from a fresh warmup.
 * - A sticky member (it holds but left the ranking) keeps running, but an order that would open or add on it is
 *   refused, re-arming the rule that sent it as a rejection does: only reducing and closing orders pass.
 */
internal class SetMemberInstances(
    private val template: SetMemberTemplate,
    private val hub: CandleHub,
    private val ctx: StrategyContext,
    private val emit: (Signal) -> Unit,
    private val warmup: (HubKey, Int) -> List<Candle>,
) {
    private val instances = LinkedHashMap<String, CompiledStrategy>()
    private val sticky = HashSet<String>()
    private var refused = 0L

    /** The members with a running instance, in the order they joined. */
    val members: Set<String> get() = instances.keys

    /** Whether [member] is sticky: held while out of the ranking. */
    fun isSticky(member: String): Boolean = member in sticky

    /** Applies every change of [step], in its order. */
    fun apply(step: MembershipStep) {
        for (change in step.changes) {
            when (change.change) {
                MembershipChange.JOIN -> join(change.member)
                MembershipChange.RESTORE -> {
                    join(change.member)
                    if (change.rank == null) sticky += change.member
                }
                MembershipChange.LEAVE, MembershipChange.RELEASE -> leave(change.member)
                MembershipChange.STICK -> sticky += change.member
                MembershipChange.STAY -> sticky -= change.member
            }
        }
    }

    private fun join(member: String) {
        val instance = template.compile(member)
        val owner = owner(member)
        for (key in instance.declaredStreams.values.toSet() + instance.retentionByKey.keys) {
            hub.register(key, instance.retentionByKey[key] ?: 1, owner)
        }
        val key = instance.declaredStreams.getValue(template.alias(member))
        if (hub.seededHistory(key).isEmpty()) {
            val bars = warmup(key, instance.retentionByKey[key] ?: 1)
            if (bars.isNotEmpty()) hub.seed(key, bars)
        }
        instance.bindToHubAs(owner, hub, ctx) { signal ->
            if (passes(member, signal)) emit(signal) else refuse(instance, owner, signal)
        }
        instances[member] = instance
    }

    private fun leave(member: String) {
        hub.unregister(owner(member))
        instances.remove(member)
        sticky -= member
    }

    private fun passes(
        member: String,
        signal: Signal,
    ): Boolean = member !in sticky || signal !is Signal.Submit || !opens(signal.request)

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

    private fun opens(request: OrderRequest): Boolean {
        val position = ctx.positions.positionFor(request.symbol)
        val held = position?.quantity?.signum() ?: 0
        return held == 0 || (held > 0) == (request.side == Side.BUY)
    }

    private fun owner(member: String): String = "${ctx.strategyId}/${template.set}/$member"
}
