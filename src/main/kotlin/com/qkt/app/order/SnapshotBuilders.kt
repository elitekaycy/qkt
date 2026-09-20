package com.qkt.app.order

import com.qkt.execution.OrderRequest
import com.qkt.execution.isCompositeShape
import com.qkt.execution.isTerminal
import com.qkt.persistence.BracketPair
import com.qkt.persistence.PersistedOcoLeg

// Builders for the persisted views of order state. Each reads the books and returns what the
// persistor stores; none mutates anything. Composite wrappers (OTO, ScaleOut, a pre-fill
// bracket) are stored in place of their atomic parent so a restart can re-arm the children.

/** Every strategy's restorable pending orders, keyed by the id they restore under. */
internal fun pendingOrdersByStrategy(
    book: OrderBook,
    children: PendingChildBook,
    brackets: BracketBook,
    scaleOuts: ScaleOutRecovery,
): MutableMap<String, MutableMap<String, OrderRequest>> {
    val pendingByStrategy: MutableMap<String, MutableMap<String, OrderRequest>> = mutableMapOf()
    val unarmedChildren = children.unarmedChildIds()
    for ((id, managed) in book.orders) {
        if (!managed.state.isTerminal) {
            val sid = managed.request.strategyId
            if (sid.isBlank()) continue
            // Composite parents are handled below or by their dedicated recovery state.
            if (managed.request.isCompositeShape()) continue
            if (id in unarmedChildren) continue
            pendingByStrategy.getOrPut(sid) { mutableMapOf() }[id] = managed.request
        }
    }
    for ((parentId, oto) in children.pendingOtos) {
        val strategyId = oto.strategyId
        if (strategyId.isBlank() || book[parentId]?.state?.isTerminal != false) continue
        // Replace the atomic parent snapshot with the wrapper so restart can re-arm children.
        pendingByStrategy.getOrPut(strategyId) { mutableMapOf() }[parentId] = oto
    }
    scaleOuts.overlay(pendingByStrategy)
    for ((entryId, bracket) in brackets.preFill) {
        if (book[entryId]?.state?.isTerminal == true) continue
        val sid = bracket.strategyId
        if (sid.isBlank()) continue
        pendingByStrategy.getOrPut(sid) { mutableMapOf() }[entryId] = bracket
    }
    for ((id, managed) in book.orders) {
        val bracket = managed.request as? OrderRequest.Bracket ?: continue
        if (managed.state.isTerminal || id in brackets.preFill || bracket in brackets.preFill.values) continue
        val sid = bracket.strategyId
        if (sid.isBlank()) continue
        pendingByStrategy.getOrPut(sid) { mutableMapOf() }[id] = bracket
    }
    return pendingByStrategy
}

/** [strategyId]'s restorable pending orders, as written synchronously before a venue submit. */
internal fun recoveryPendingOrders(
    strategyId: String,
    book: OrderBook,
    children: PendingChildBook,
    brackets: BracketBook,
    scaleOuts: ScaleOutRecovery,
): Map<String, OrderRequest> {
    val unarmedChildren = children.unarmedChildIds()
    val result =
        book.orders
            .asSequence()
            .filter { (id, managed) ->
                managed.request.strategyId == strategyId &&
                    !managed.state.isTerminal &&
                    !managed.request.isCompositeShape() &&
                    id !in unarmedChildren
            }.associateTo(linkedMapOf()) { (id, managed) -> id to managed.request }
    children.pendingOtos.forEach { (parentId, oto) ->
        if (oto.strategyId == strategyId && book[parentId]?.state?.isTerminal == false) {
            result[parentId] = oto
        }
    }
    result.putAll(scaleOuts.recoverySnapshot(strategyId))
    for ((entryId, bracket) in brackets.preFill) {
        if (bracket.strategyId == strategyId && book[entryId]?.state?.isTerminal != true) {
            result[entryId] = bracket
        }
    }
    for ((id, managed) in book.orders) {
        val bracket = managed.request as? OrderRequest.Bracket ?: continue
        if (bracket.strategyId != strategyId || managed.state.isTerminal) continue
        if (id in brackets.preFill || bracket in brackets.preFill.values) continue
        result[id] = bracket
    }
    return result
}

/** Stop-loss / take-profit sibling pairs of every linked order, grouped by strategy. */
internal fun bracketPairsByStrategy(
    book: OrderBook,
    siblings: SiblingLinks,
): Map<String, List<BracketPair>> {
    val pairsByStrategy: MutableMap<String, MutableList<BracketPair>> = mutableMapOf()
    for ((entryId, siblingIds) in siblings.all) {
        val entry = book[entryId] ?: continue
        val sid = entry.request.strategyId
        if (sid.isBlank()) continue
        val sl =
            siblingIds.firstOrNull {
                it.contains("-sl") ||
                    (book[it]?.request is OrderRequest.Stop)
            }
        val tp = siblingIds.firstOrNull { it != sl }
        pairsByStrategy.getOrPut(sid) { mutableListOf() }.add(
            BracketPair(
                entryClientOrderId = entryId,
                stopLossClientOrderId = sl,
                takeProfitClientOrderId = tp,
                legId = null,
            ),
        )
    }
    return pairsByStrategy
}

/** Live linked legs that already carry a venue ticket, grouped by strategy. */
internal fun ocoLegsByStrategy(
    book: OrderBook,
    siblings: SiblingLinks,
): Map<String, List<PersistedOcoLeg>> {
    val ocoLegsByStrategy: MutableMap<String, MutableList<PersistedOcoLeg>> = mutableMapOf()
    for ((legId, siblingIds) in siblings.all) {
        val managed = book[legId] ?: continue
        if (managed.state.isTerminal) continue
        val ticket = managed.brokerOrderId ?: continue
        val sid = managed.request.strategyId
        if (sid.isBlank()) continue
        ocoLegsByStrategy.getOrPut(sid) { mutableListOf() }.add(
            PersistedOcoLeg(
                clientOrderId = legId,
                brokerOrderId = ticket,
                strategyId = sid,
                request = managed.request,
                siblingIds = siblingIds,
            ),
        )
    }
    return ocoLegsByStrategy
}
