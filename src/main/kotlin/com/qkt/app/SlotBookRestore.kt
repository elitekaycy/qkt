package com.qkt.app

import com.qkt.dsl.compile.SlotBookOwner
import com.qkt.instrument.EventTerms
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.SlotSymbol
import com.qkt.positions.StrategyLegReads
import com.qkt.strategy.Strategy

/**
 * Rebuilds the books of [strategy]'s event slots after a restart, from the contracts it holds again among [restored]
 * (the symbols reconcile restored for it): a held contract of a slot's family, by its terms in [instruments], joins
 * that slot's book, oldest leg first, so `POSITION.<slot>`, reduce-first orders and `CLOSE` see what the slot held
 * before. A family two slots of the strategy share has one net position per contract on a netting account; each
 * contract joins the first of them in declaration order.
 */
internal fun restoreSlotBooks(
    strategyId: String,
    strategy: Strategy,
    restored: Collection<String>,
    positions: StrategyLegReads,
    instruments: InstrumentRegistry,
) {
    val owner = strategy as? SlotBookOwner ?: return
    val slots =
        owner.declaredStreams.mapNotNull { (alias, key) -> SlotSymbol.parse(key.qktSymbol)?.let { alias to it.root } }
    val held =
        restored.mapNotNull { symbol ->
            val root = (instruments.lookup(symbol)?.derivative as? EventTerms)?.root
            val alias = slots.firstOrNull { it.second == root }?.first
            val legs = positions.legBookFor(strategyId, symbol)?.takeIf { it.netQuantity().signum() != 0 }?.all()
            if (alias == null || legs == null) null else Triple(alias, symbol, legs.minOf { it.openedAt })
        }
    for ((alias, symbol) in held.sortedWith(compareBy({ it.third }, { it.second }))) {
        owner.slotBooks.opened(strategyId, alias, symbol)
    }
}
