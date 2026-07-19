package com.qkt.app

import com.qkt.common.Side
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.ExitContext
import com.qkt.dsl.compile.ExitHookRef
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import com.qkt.persistence.PersistedExitHookBinding
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
    private val persistor: StatePersistor,
) {
    private data class Runtime(
        val strategy: DslCompiledStrategy,
        val emit: (Signal) -> Unit,
    )

    private data class Binding(
        val id: String,
        val strategyId: String,
        val symbol: String,
        val entrySide: Side,
        val ref: ExitHookRef,
        val entryOrderIds: Set<String>,
        val stopOrderIds: Set<String>,
        val takeProfitOrderIds: Set<String>,
        val closeOrderIds: MutableSet<String> = linkedSetOf(),
        val brokerTickets: MutableSet<String> = linkedSetOf(),
        var activeQuantity: BigDecimal = BigDecimal.ZERO,
        var exitQuantity: BigDecimal = BigDecimal.ZERO,
        var exitPnl: BigDecimal = BigDecimal.ZERO,
        var fired: Boolean = false,
    )

    private data class RequestIds(
        val entries: Set<String>,
        val stops: Set<String>,
        val takeProfits: Set<String>,
    )

    private data class PendingDispatch(
        val runtime: Runtime,
        val ref: ExitHookRef,
        val context: ExitContext,
        val timestamp: Long,
    ) {
        fun execute() {
            runtime.strategy.executeExitHook(ref, context, timestamp).forEach(runtime.emit)
        }
    }

    private val runtimes = mutableMapOf<String, Runtime>()
    private val bindings = mutableMapOf<String, Binding>()
    private val byStrategy = mutableMapOf<String, MutableSet<String>>()
    private val byOrderId = mutableMapOf<String, MutableSet<String>>()
    private val byBrokerTicket = mutableMapOf<String, MutableSet<String>>()
    private val bySymbol = mutableMapOf<String, MutableSet<String>>()
    private val readyDispatches = mutableMapOf<Long, MutableList<PendingDispatch>>()

    /** Bind a compiled DSL strategy and its normal gated emit path. */
    fun bind(
        strategyId: String,
        strategy: DslCompiledStrategy,
        emit: (Signal) -> Unit,
    ) {
        runtimes[strategyId] = Runtime(strategy, emit)
        restore(strategyId, strategy)
    }

    /** Register a risk-approved request before it reaches the broker. */
    fun register(
        strategyId: String,
        request: OrderRequest,
        ref: ExitHookRef,
    ) {
        val runtime = runtimes[strategyId] ?: error("Exit-hook strategy '$strategyId' is not bound")
        require(runtime.strategy.exitHookReferences()[ref.definitionId] == ref) {
            "Exit-hook definition '${ref.definitionId}' is missing or its fingerprint changed"
        }
        val ids = requestIds(request)
        val binding =
            Binding(
                id = "${request.id}:${ref.definitionId}",
                strategyId = strategyId,
                symbol = request.symbol,
                entrySide = request.side,
                ref = ref,
                entryOrderIds = ids.entries,
                stopOrderIds = ids.stops,
                takeProfitOrderIds = ids.takeProfits,
            )
        remove(binding.id)
        bindings[binding.id] = binding
        index(byStrategy, strategyId, binding.id)
        for (id in ids.entries + ids.stops + ids.takeProfits) {
            byOrderId.getOrPut(orderKey(strategyId, id)) { linkedSetOf() }.add(binding.id)
        }
        bySymbol.getOrPut(symbolKey(strategyId, request.symbol)) { linkedSetOf() }.add(binding.id)
        persist(strategyId)
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
        if (byStrategy[strategyId].isNullOrEmpty()) return
        if (request !is OrderRequest.Market) return
        val matches = linkedSetOf<String>()
        request.closesLegId?.let { legId ->
            byOrderId[orderKey(strategyId, legId)]?.let(matches::addAll)
        }
        request.closesTicket?.let { ticket ->
            byBrokerTicket[ticketKey(strategyId, ticket)]?.let(matches::addAll)
        }
        if (matches.isEmpty()) return
        for (bindingId in matches) {
            val binding = bindings[bindingId] ?: continue
            if (binding.closeOrderIds.add(request.id)) {
                index(byOrderId, orderKey(strategyId, request.id), bindingId)
            }
        }
        persist(strategyId)
    }

    /** Drop a binding whose entry was rejected before any position could open. */
    fun onRejected(event: BrokerEvent.OrderRejected) {
        if (byStrategy[event.strategyId].isNullOrEmpty()) return
        val ids = byOrderId[orderKey(event.strategyId, event.clientOrderId)]?.toList() ?: return
        ids
            .filter { id ->
                val binding = bindings[id] ?: return@filter false
                event.clientOrderId in binding.entryOrderIds && binding.activeQuantity.signum() == 0
            }.forEach(::remove)
        dropCloseCorrelation(event.strategyId, event.clientOrderId, ids)
    }

    /** Drop an unfilled parent entry that expired or was cancelled. */
    fun onCancelled(event: BrokerEvent.OrderCancelled) {
        if (byStrategy[event.strategyId].isNullOrEmpty()) return
        val ids = byOrderId[orderKey(event.strategyId, event.clientOrderId)]?.toList() ?: return
        ids
            .filter { id ->
                val binding = bindings[id] ?: return@filter false
                event.clientOrderId in binding.entryOrderIds && binding.activeQuantity.signum() == 0
            }.forEach(::remove)
        dropCloseCorrelation(event.strategyId, event.clientOrderId, ids)
    }

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
        if (byStrategy[event.strategyId].isNullOrEmpty()) return
        val exact =
            linkedSetOf<String>().apply {
                byOrderId[orderKey(event.strategyId, event.clientOrderId)]?.let(::addAll)
                event.brokerOrderId
                    ?.takeIf(String::isNotBlank)
                    ?.let { byBrokerTicket[ticketKey(event.strategyId, it)] }
                    ?.let(::addAll)
            }
        val candidates =
            if (exact.isNotEmpty()) {
                exact
            } else if (reducedExposure) {
                bySymbol[symbolKey(event.strategyId, event.symbol)]?.toList().orEmpty()
            } else {
                emptyList()
            }
        val closing = mutableListOf<Binding>()
        for (bindingId in candidates) {
            val binding = bindings[bindingId] ?: continue
            if (binding.fired) continue
            val isEntry =
                event.clientOrderId in binding.entryOrderIds &&
                    event.side == binding.entrySide &&
                    event.exitReason == null
            if (isEntry) {
                binding.activeQuantity = binding.activeQuantity.add(event.quantity)
                event.brokerOrderId
                    ?.takeIf(String::isNotBlank)
                    ?.let { ticket ->
                        if (binding.brokerTickets.add(ticket)) {
                            index(byBrokerTicket, ticketKey(binding.strategyId, ticket), binding.id)
                        }
                    }
                persist(binding.strategyId)
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
                persist(binding.strategyId)
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
            dispatch(binding, event, reason, deferDispatch)
        }
    }

    /** Execute hook actions prepared by a deferred [onFill] call for this stamped event. */
    fun dispatchReady(event: BrokerEvent.OrderFilled) {
        readyDispatches.remove(event.sequenceId)?.forEach(PendingDispatch::execute)
    }

    private fun dispatch(
        binding: Binding,
        event: BrokerEvent.OrderFilled,
        reason: ExitReason,
        defer: Boolean,
    ) {
        binding.fired = true
        val runtime = runtimes.getValue(binding.strategyId)
        val context =
            ExitContext(
                price = event.price,
                side = event.side,
                quantity = binding.exitQuantity,
                pnl = binding.exitPnl,
                reason = reason,
            )
        remove(binding.id)
        val pending = PendingDispatch(runtime, binding.ref, context, event.timestamp)
        if (defer) {
            readyDispatches.getOrPut(event.sequenceId) { mutableListOf() }.add(pending)
        } else {
            pending.execute()
        }
    }

    private fun remove(bindingId: String) {
        val binding = bindings.remove(bindingId) ?: return
        unindex(byStrategy, binding.strategyId, bindingId)
        for (id in binding.entryOrderIds + binding.stopOrderIds + binding.takeProfitOrderIds + binding.closeOrderIds) {
            unindex(byOrderId, orderKey(binding.strategyId, id), bindingId)
        }
        for (ticket in binding.brokerTickets) {
            unindex(byBrokerTicket, ticketKey(binding.strategyId, ticket), bindingId)
        }
        val key = symbolKey(binding.strategyId, binding.symbol)
        bySymbol[key]?.let { values ->
            values.remove(bindingId)
            if (values.isEmpty()) bySymbol.remove(key)
        }
        persist(binding.strategyId)
    }

    private fun dropCloseCorrelation(
        strategyId: String,
        orderId: String,
        bindingIds: List<String>,
    ) {
        var changed = false
        for (bindingId in bindingIds) {
            val binding = bindings[bindingId] ?: continue
            if (binding.closeOrderIds.remove(orderId)) {
                unindex(byOrderId, orderKey(strategyId, orderId), bindingId)
                changed = true
            }
        }
        if (changed) persist(strategyId)
    }

    private fun restore(
        strategyId: String,
        strategy: DslCompiledStrategy,
    ) {
        for (saved in persistor.loadExitHooks(strategyId)) {
            require(saved.strategyId == strategyId) {
                "Persisted exit-hook '${saved.bindingId}' belongs to ${saved.strategyId}, not $strategyId"
            }
            val ref = ExitHookRef(saved.definitionId, saved.fingerprint)
            require(strategy.exitHookReferences()[ref.definitionId] == ref) {
                "Persisted exit-hook definition '${ref.definitionId}' is missing or its fingerprint changed"
            }
            val binding =
                Binding(
                    id = saved.bindingId,
                    strategyId = saved.strategyId,
                    symbol = saved.symbol,
                    entrySide = saved.entrySide,
                    ref = ref,
                    entryOrderIds = saved.entryOrderIds.toSet(),
                    stopOrderIds = saved.stopOrderIds.toSet(),
                    takeProfitOrderIds = saved.takeProfitOrderIds.toSet(),
                    closeOrderIds = saved.closeOrderIds.toMutableSet(),
                    brokerTickets = saved.brokerTickets.toMutableSet(),
                    activeQuantity = saved.activeQuantity,
                    exitQuantity = saved.exitQuantity,
                    exitPnl = saved.exitPnl,
                )
            bindings[binding.id] = binding
            index(byStrategy, strategyId, binding.id)
            val orderIds =
                binding.entryOrderIds + binding.stopOrderIds + binding.takeProfitOrderIds + binding.closeOrderIds
            for (id in orderIds) {
                index(byOrderId, orderKey(strategyId, id), binding.id)
            }
            for (ticket in binding.brokerTickets) {
                index(byBrokerTicket, ticketKey(strategyId, ticket), binding.id)
            }
            bySymbol.getOrPut(symbolKey(strategyId, binding.symbol)) { linkedSetOf() }.add(binding.id)
        }
    }

    private fun persist(strategyId: String) {
        persistor.saveExitHooks(
            strategyId,
            byStrategy[strategyId]
                .orEmpty()
                .mapNotNull(bindings::get)
                .filterNot { it.fired }
                .map {
                    PersistedExitHookBinding(
                        bindingId = it.id,
                        strategyId = it.strategyId,
                        symbol = it.symbol,
                        entrySide = it.entrySide,
                        definitionId = it.ref.definitionId,
                        fingerprint = it.ref.fingerprint,
                        entryOrderIds = it.entryOrderIds.toList(),
                        stopOrderIds = it.stopOrderIds.toList(),
                        takeProfitOrderIds = it.takeProfitOrderIds.toList(),
                        closeOrderIds = it.closeOrderIds.toList(),
                        brokerTickets = it.brokerTickets.toList(),
                        activeQuantity = it.activeQuantity,
                        exitQuantity = it.exitQuantity,
                        exitPnl = it.exitPnl,
                    )
                },
        )
    }

    private fun requestIds(request: OrderRequest): RequestIds =
        when (request) {
            is OrderRequest.Bracket ->
                RequestIds(
                    // Attached/fallback paths key fills under entry.id; a venue with
                    // native BRACKET but no position-modify support may retain parent id.
                    entries = setOf(request.id, request.entry.id),
                    stops = setOf("${request.id}-sl"),
                    takeProfits = setOf("${request.id}-tp"),
                )
            is OrderRequest.Stack -> {
                val entries = request.plan.layers.mapTo(linkedSetOf()) { "${request.id}-l${it.index}" }
                RequestIds(
                    entries = entries,
                    stops = entries.mapTo(linkedSetOf()) { "$it-sl" },
                    takeProfits = entries.mapTo(linkedSetOf()) { "$it-tp" },
                )
            }
            is OrderRequest.StandaloneOCO, is OrderRequest.OTO, is OrderRequest.ScaleOut, is OrderRequest.TimeExit ->
                error("Exit hooks do not support ${request::class.simpleName} parents in v1")
            else -> RequestIds(setOf(request.id), emptySet(), emptySet())
        }

    private fun orderKey(
        strategyId: String,
        orderId: String,
    ): String = "$strategyId\u0000$orderId"

    private fun symbolKey(
        strategyId: String,
        symbol: String,
    ): String = "$strategyId\u0000$symbol"

    private fun ticketKey(
        strategyId: String,
        ticket: String,
    ): String = "$strategyId\u0000$ticket"

    private fun index(
        index: MutableMap<String, MutableSet<String>>,
        key: String,
        bindingId: String,
    ) {
        index.getOrPut(key) { linkedSetOf() }.add(bindingId)
    }

    private fun unindex(
        index: MutableMap<String, MutableSet<String>>,
        key: String,
        bindingId: String,
    ) {
        index[key]?.let { values ->
            values.remove(bindingId)
            if (values.isEmpty()) index.remove(key)
        }
    }
}
