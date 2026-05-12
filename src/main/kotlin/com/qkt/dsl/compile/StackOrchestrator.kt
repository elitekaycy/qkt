package com.qkt.dsl.compile

import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.strategy.Signal
import java.math.BigDecimal

/**
 * Per-strategy registry of live [StackEngine]s.
 *
 * Phase 27: holds one engine per active PRIMARY leg that has `STACK_AT` clauses.
 * The runtime feeds the orchestrator three event streams:
 *   - [onPrimaryFilled]: when a primary BUY/SELL fills — construct an engine for the
 *     parent leg with its fill price + the action's compiled tiers
 *   - [onTick]: per market tick — dispatch to every engine whose [parentSymbol] matches
 *   - [onPrimaryClosed]: when the parent leg closes — destroy the engine
 *
 * Stack legs themselves do NOT spawn nested stack engines: only primary fills register
 * with the orchestrator.
 */
class StackOrchestrator(
    private val clock: Clock,
    private val onStackBracketEmit: (bracket: OrderRequest.Bracket, parentLegId: String) -> Unit = { _, _ -> },
    private val emit: (Signal) -> Unit,
) {
    private val engines: MutableMap<String, StackEngine> = mutableMapOf()

    fun onPrimaryFilled(
        parentLegId: String,
        parentSymbol: String,
        parentSide: Side,
        parentEntryPrice: BigDecimal,
        tiers: List<CompiledStackTier>,
        closeWatchIds: Set<String> = emptySet(),
    ) {
        if (tiers.isEmpty()) return
        check(parentLegId !in engines) { "StackEngine already registered for $parentLegId" }
        val engineEmit: (Signal) -> Unit = { sig ->
            if (sig is Signal.Submit) {
                val req = sig.request
                if (req is OrderRequest.Bracket) onStackBracketEmit(req, parentLegId)
            }
            emit(sig)
        }
        engines[parentLegId] =
            StackEngine(
                parentLegId = parentLegId,
                parentSymbol = parentSymbol,
                closeWatchIds = closeWatchIds,
                parentSide = parentSide,
                parentEntryPrice = parentEntryPrice,
                tiers = tiers,
                clock = clock,
                emit = engineEmit,
            )
    }

    fun onTick(
        symbol: String,
        price: BigDecimal,
    ) {
        for (engine in engines.values) {
            if (engine.parentSymbol == symbol) engine.onTick(price)
        }
    }

    fun onPrimaryClosed(parentLegId: String) {
        engines.remove(parentLegId)
    }

    /**
     * Treat [clientOrderId] as a potential close signal. Iterates the registered engines
     * and removes any whose [StackEngine.closeWatchIds] contains the id. A typical use:
     * BrokerEvent.OrderFilled arrives for a bracket's TP or SL — the parent leg has just
     * closed via its risk children, so its stack engine must stop firing.
     */
    fun onPossibleClose(clientOrderId: String) {
        val toRemove =
            engines.values
                .filter { clientOrderId in it.closeWatchIds }
                .map { it.parentLegId }
        for (legId in toRemove) engines.remove(legId)
    }

    fun activeCount(): Int = engines.size

    fun hasEngineFor(parentLegId: String): Boolean = parentLegId in engines
}
