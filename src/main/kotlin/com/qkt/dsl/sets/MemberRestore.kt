package com.qkt.dsl.sets

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

    /** Records [members] as what [set] of [strategyId] has active now, when the session persists state. */
    fun own(
        strategyId: String,
        set: String,
        members: List<String>,
    ) {
        persistor?.let { SetOwnership.save(it, strategyId, set, members) }
    }
}
