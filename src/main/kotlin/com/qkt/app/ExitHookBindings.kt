package com.qkt.app

import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.ExitHookRef
import com.qkt.events.BrokerEvent
import com.qkt.persistence.StatePersistor

/**
 * The live [ExitHookBinding]s and their indexes by strategy, order id, broker ticket and
 * strategy/symbol, persisted per strategy on every change. Owned by [ExitHookManager], the only
 * caller; every lookup is a keyed map read.
 */
internal class ExitHookBindings(
    private val persistor: StatePersistor,
) {
    private val bindings = mutableMapOf<String, ExitHookBinding>()
    private val byStrategy = mutableMapOf<String, MutableSet<String>>()
    private val byOrderId = mutableMapOf<String, MutableSet<String>>()
    private val byBrokerTicket = mutableMapOf<String, MutableSet<String>>()
    private val bySymbol = mutableMapOf<String, MutableSet<String>>()

    /** True when [strategyId] has at least one binding. */
    fun hasAny(strategyId: String): Boolean = !byStrategy[strategyId].isNullOrEmpty()

    fun get(bindingId: String): ExitHookBinding? = bindings[bindingId]

    fun forOrder(
        strategyId: String,
        orderId: String,
    ): MutableSet<String>? = byOrderId[orderKey(strategyId, orderId)]

    fun forTicket(
        strategyId: String,
        ticket: String,
    ): MutableSet<String>? = byBrokerTicket[ticketKey(strategyId, ticket)]

    fun forSymbol(
        strategyId: String,
        symbol: String,
    ): MutableSet<String>? = bySymbol[symbolKey(strategyId, symbol)]

    /** Add [binding], replacing one with the same id, indexed under [ids]. */
    fun register(
        binding: ExitHookBinding,
        ids: ExitHookRequestIds,
    ) {
        val strategyId = binding.strategyId
        remove(binding.id)
        bindings[binding.id] = binding
        index(byStrategy, strategyId, binding.id)
        for (id in ids.entries + ids.stops + ids.takeProfits) {
            byOrderId.getOrPut(orderKey(strategyId, id)) { linkedSetOf() }.add(binding.id)
        }
        bySymbol.getOrPut(symbolKey(strategyId, binding.symbol)) { linkedSetOf() }.add(binding.id)
        persist(strategyId)
    }

    /** Correlate close order [orderId] with each of [bindingIds] and persist [strategyId]. */
    fun correlateClose(
        strategyId: String,
        orderId: String,
        bindingIds: Set<String>,
    ) {
        for (bindingId in bindingIds) {
            val binding = bindings[bindingId] ?: continue
            if (binding.closeOrderIds.add(orderId)) {
                index(byOrderId, orderKey(strategyId, orderId), bindingId)
            }
        }
        persist(strategyId)
    }

    /** Book an entry fill on [binding]: grow its active quantity and index the venue ticket. */
    fun recordEntryFill(
        binding: ExitHookBinding,
        event: BrokerEvent.OrderFilled,
    ) {
        binding.activeQuantity = binding.activeQuantity.add(event.quantity)
        event.brokerOrderId
            ?.takeIf(String::isNotBlank)
            ?.let { ticket ->
                if (binding.brokerTickets.add(ticket)) {
                    index(byBrokerTicket, ticketKey(binding.strategyId, ticket), binding.id)
                }
            }
        persist(binding.strategyId)
    }

    fun remove(bindingId: String) {
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

    fun dropCloseCorrelation(
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

    /** Reload [strategyId]'s saved bindings, rejecting any whose hook definition changed. */
    fun restore(
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
            val binding = saved.toBinding(ref)
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

    /** Save [strategyId]'s unfired bindings. */
    fun persist(strategyId: String) {
        persistor.saveExitHooks(
            strategyId,
            byStrategy[strategyId]
                .orEmpty()
                .mapNotNull(bindings::get)
                .filterNot { it.fired }
                .map { it.toPersisted() },
        )
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
