package com.qkt.observe

import com.qkt.derivatives.options.chain.OptionRootSymbol
import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import com.qkt.events.Event
import com.qkt.events.OrderEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.TradeEvent

/**
 * Which market data of a session that feeds whole option roots (`OPTIONS:<VENUE>.<ROOT>`) the audit
 * journal keeps. A root feed delivers every listed contract's quotes, tens of thousands of contracts,
 * while a decision reads only a few of them: structures choose legs from the account's chain book
 * (recorded every `chain_snapshot_seconds` by the gateway connector, the same series replay reads),
 * and an order, fill or mark involves one contract. So the ticks and candles of a chain-only
 * contract (covered by a fed root, not itself a declared stream) are journaled only from the moment
 * the session orders, fills, settles or holds it; until then they are omitted, not journaled per
 * quote. Every other event, and every quote of a session without a root feed, is journaled.
 *
 * Runs on the bus thread. Per event it costs one hash lookup for market data (the contract's
 * classification is cached on first sight) and one more for a chain-only contract; [held] is asked
 * only for chain-only contracts not yet traded.
 */
class AuditChainScope(
    streams: Collection<String>,
    private val held: (String) -> Boolean,
) {
    private val roots = streams.mapNotNull { OptionRootSymbol.parse(it).getOrNull() }
    private val declared = streams.toHashSet()
    private val chainOnly = HashMap<String, Boolean>()
    private val traded = HashSet<String>()

    /** Whether [event] belongs in the journal. */
    fun journals(event: Event): Boolean {
        if (roots.isEmpty()) return true
        val symbol = marketDataSymbol(event)
        if (symbol == null) {
            tradedSymbol(event)?.let { if (isChainOnly(it)) traded.add(it) }
            return true
        }
        if (!isChainOnly(symbol) || symbol in traded) return true
        if (!held(symbol)) return false
        traded.add(symbol)
        return true
    }

    private fun isChainOnly(symbol: String): Boolean =
        chainOnly.getOrPut(symbol) { symbol !in declared && roots.any { it.covers(symbol) } }

    private fun tradedSymbol(event: Event): String? =
        when (event) {
            is OrderEvent -> event.request.symbol
            is RiskRejectedEvent -> event.request.symbol
            is BrokerEvent.OrderFilled -> event.symbol
            is BrokerEvent.OrderPartiallyFilled -> event.symbol
            is BrokerEvent.PositionReconciled -> event.symbol
            is TradeEvent -> event.trade.symbol
            is ContractSettled -> event.symbol
            else -> null
        }
}
