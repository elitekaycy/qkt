package com.qkt.app

import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.ExitHookRef
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import com.qkt.persistence.StatePersistor
import com.qkt.strategy.Signal
import java.math.BigDecimal

/**
 * Pipeline-owned lifecycle for one-shot DSL exit hooks.
 *
 * All lookups are indexed by strategy, order id, broker ticket, or strategy/symbol.
 * Strategies without active hooks return after one strategy-map lookup.
 */
class ExitHookManager(
    persistor: StatePersistor,
) {
    private val bindings = ExitHookBindings(persistor)
    private val dispatcher = ExitHookDispatcher(bindings)

    /** Bind a compiled DSL strategy and its normal gated emit path. */
    fun bind(
        strategyId: String,
        strategy: DslCompiledStrategy,
        emit: (Signal) -> Unit,
    ) {
        dispatcher.bind(strategyId, strategy, emit)
        bindings.restore(strategyId, strategy)
    }

    /** Register a risk-approved request before it reaches the broker. */
    fun register(
        strategyId: String,
        request: OrderRequest,
        ref: ExitHookRef,
    ) {
        val strategy = dispatcher.strategyFor(strategyId) ?: error("Exit-hook strategy '$strategyId' is not bound")
        require(strategy.exitHookReferences()[ref.definitionId] == ref) {
            "Exit-hook definition '${ref.definitionId}' is missing or its fingerprint changed"
        }
        val ids = exitHookRequestIds(request)
        val binding =
            ExitHookBinding(
                id = "${request.id}:${ref.definitionId}",
                strategyId = strategyId,
                symbol = request.symbol,
                entrySide = request.side,
                ref = ref,
                entryOrderIds = ids.entries,
                stopOrderIds = ids.stops,
                takeProfitOrderIds = ids.takeProfits,
            )
        bindings.register(binding, ids)
    }

    /**
     * Correlate an approved explicit close with the hook-bearing entry it targets.
     *
     * Engine-generated bracket ids are known at [register] time. This path covers
     * manual DSL closes that target a model leg or a venue ticket.
     */
    fun trackCloseRequest(
        strategyId: String,
        request: OrderRequest,
    ) {
        if (!bindings.hasAny(strategyId)) return
        if (request !is OrderRequest.Market) return
        val matches = linkedSetOf<String>()
        request.closesLegId?.let { legId ->
            bindings.forOrder(strategyId, legId)?.let(matches::addAll)
        }
        request.closesTicket?.let { ticket ->
            bindings.forTicket(strategyId, ticket)?.let(matches::addAll)
        }
        if (matches.isEmpty()) return
        bindings.correlateClose(strategyId, request.id, matches)
    }

    /** Drop a binding whose entry was rejected before any position could open. */
    fun onRejected(event: BrokerEvent.OrderRejected) = dropUnfilledEntry(event.strategyId, event.clientOrderId)

    /** Drop an unfilled parent entry that expired or was cancelled. */
    fun onCancelled(event: BrokerEvent.OrderCancelled) = dropUnfilledEntry(event.strategyId, event.clientOrderId)

    /**
     * Consume an accounted fill. [strategyAfterQuantity] is the post-fill net strategy
     * quantity, [reducedExposure] says accounting observed a reduction/flip, and
     * [netRealizedPnl] is the exact amount already booked by the pipeline. Set
     * [deferDispatch] when order-lifecycle handlers must finish before child submission.
     */
    fun onFill(
        event: BrokerEvent.OrderFilled,
        netRealizedPnl: BigDecimal,
        strategyAfterQuantity: BigDecimal,
        reducedExposure: Boolean,
        deferDispatch: Boolean = false,
    ) {
        if (!bindings.hasAny(event.strategyId)) return
        val exact =
            linkedSetOf<String>().apply {
                bindings.forOrder(event.strategyId, event.clientOrderId)?.let(::addAll)
                event.brokerOrderId
                    ?.takeIf(String::isNotBlank)
                    ?.let { bindings.forTicket(event.strategyId, it) }
                    ?.let(::addAll)
            }
        val candidates =
            if (exact.isNotEmpty()) {
                exact
            } else if (reducedExposure) {
                bindings.forSymbol(event.strategyId, event.symbol)?.toList().orEmpty()
            } else {
                emptyList()
            }
        val closing = mutableListOf<ExitHookBinding>()
        for (bindingId in candidates) {
            val binding = bindings.get(bindingId) ?: continue
            if (binding.fired) continue
            val isEntry =
                event.clientOrderId in binding.entryOrderIds &&
                    event.side == binding.entrySide &&
                    event.exitReason == null
            if (isEntry) {
                bindings.recordEntryFill(binding, event)
                continue
            }
            if (event.side == binding.entrySide || binding.activeQuantity.signum() == 0) continue
            closing.add(binding)
        }
        val coveredQuantity =
            closing
                .fold(BigDecimal.ZERO) { total, binding -> total.add(binding.activeQuantity) }
                .min(event.quantity)
        var remainingQuantity = event.quantity
        var remainingPnl = netRealizedPnl
        for ((index, binding) in closing.withIndex()) {
            if (remainingQuantity.signum() == 0) break
            val quantity = binding.activeQuantity.min(remainingQuantity)
            val pnl =
                if (coveredQuantity.compareTo(event.quantity) == 0 && index == closing.lastIndex) {
                    remainingPnl
                } else {
                    netRealizedPnl
                        .multiply(quantity)
                        .divide(event.quantity, java.math.MathContext.DECIMAL128)
                }
            binding.activeQuantity = binding.activeQuantity.subtract(quantity)
            binding.exitQuantity = binding.exitQuantity.add(quantity)
            binding.exitPnl = binding.exitPnl.add(pnl)
            remainingQuantity = remainingQuantity.subtract(quantity)
            remainingPnl = remainingPnl.subtract(pnl)
            if (binding.activeQuantity.signum() != 0 && strategyAfterQuantity.signum() != 0) {
                bindings.persist(binding.strategyId)
                continue
            }
            val reason =
                when {
                    event.clientOrderId in binding.stopOrderIds -> ExitReason.STOP
                    event.clientOrderId in binding.takeProfitOrderIds -> ExitReason.TAKE_PROFIT
                    event.clientOrderId in binding.closeOrderIds -> ExitReason.CLOSE
                    event.exitReason != null -> event.exitReason
                    else -> ExitReason.CLOSE
                }
            dispatcher.dispatch(binding, event, reason, deferDispatch)
        }
    }

    /** Execute hook actions prepared by a deferred [onFill] call for this stamped event. */
    fun dispatchReady(event: BrokerEvent.OrderFilled) = dispatcher.dispatchReady(event)

    private fun dropUnfilledEntry(
        strategyId: String,
        clientOrderId: String,
    ) {
        if (!bindings.hasAny(strategyId)) return
        val ids = bindings.forOrder(strategyId, clientOrderId)?.toList() ?: return
        ids
            .filter { id ->
                val binding = bindings.get(id) ?: return@filter false
                clientOrderId in binding.entryOrderIds && binding.activeQuantity.signum() == 0
            }.forEach(bindings::remove)
        bindings.dropCloseCorrelation(strategyId, clientOrderId, ids)
    }
}
