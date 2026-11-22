package com.qkt.dsl.sets

import com.qkt.instrument.universe.MembershipStep
import com.qkt.persistence.StatePersistor
import com.qkt.strategy.StrategyContext

/**
 * What a restarted session hands a moving set's members as they join: the order ids it restored ([usedIds]), every
 * generator's persisted mark ([marks], by prefix), where member state persists ([persistor]), and the members each
 * set had active before the restart ([owned], by set name; see [SetOwnership]). Empty in a run that starts fresh, as
 * every backtest does.
 */
internal class MemberRestore {
    var usedIds: Collection<String> = emptyList()
    var marks: Map<String, Long> = emptyMap()
    var persistor: StatePersistor? = null
    var owned: Map<String, Set<String>> = emptyMap()

    /**
     * True once the session cleared the strategy's rule edges (a position closed while it was down, or an operator
     * stop flattened it): a member that joins afterwards starts with clear edges too, instead of the ones it saved.
     */
    var clearEdges: Boolean = false

    /**
     * The members of [set] (among [declared]) a restart restores: those the set had active before and the strategy
     * still holds or has an entry order working on. A position on the set's venue the set never had as a member
     * stays with whichever rule opened it.
     */
    fun heldAtStart(
        set: HostedSet,
        declared: Set<String>,
        ctx: StrategyContext,
    ): Set<String> {
        val mine = owned[set.template.set].orEmpty()
        return declared.filterTo(HashSet()) {
            val symbol = "${set.venue}:$it"
            val position = ctx.positions.positionFor(symbol)
            val holds = (position?.quantity?.signum() ?: 0) != 0
            it in mine && (holds || ctx.openOrders.entryCountFor(symbol) > 0)
        }
    }

    private val expiries = HashMap<String, MutableMap<String, Long>>()

    /**
     * Records what [set] has active after [step], when the session persists state and the step changed anything.
     * Each member's expiry is saved with it while the listing gives one, and kept once it no longer does (an expired
     * contract leaves the listing while it can still be held).
     */
    fun own(
        strategyId: String,
        set: HostedSet,
        step: MembershipStep,
    ) {
        val store = persistor?.takeIf { step.changes.isNotEmpty() } ?: return
        val name = set.template.set
        val known = expiries.getOrPut(name) { HashMap(SetOwnership.expiries(store, strategyId, name)) }
        for (member in step.active) set.membership.expiryOf(member)?.let { known[member] = it }
        known.keys.retainAll(step.active.toSet())
        SetOwnership.save(store, strategyId, name, step.active, known)
    }
}
