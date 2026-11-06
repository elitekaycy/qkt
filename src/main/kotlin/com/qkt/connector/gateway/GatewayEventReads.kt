package com.qkt.connector.gateway

import com.qkt.common.Clock
import com.qkt.connectivity.DirectChain
import com.qkt.connectivity.DirectChainContract
import com.qkt.connectivity.DirectEventVenue
import com.qkt.connectivity.DirectMarket
import com.qkt.connectivity.MarketFilter
import com.qkt.instrument.EventTerms
import com.qkt.instrument.FamilyContract
import com.qkt.instrument.FamilyEvent
import com.qkt.instrument.OpenEvents
import com.qkt.instrument.SlotSymbol
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.SlotPick
import java.math.BigDecimal

/**
 * A gateway account's event contracts for `qkt bot`: the catalog read through the gateway's listing filters
 * (`kind=event`, `category`, `series`, `status=open`), one event's contracts quoted over one subscription by its
 * series ([gatewayEventQuotes], waiting at most [quoteWaitMs]), a slot resolved as a strategy's slot picks at [clock]'s
 * now, and the paused or closing-out contracts refused by name. A contract whose listing qkt cannot read is left out.
 */
internal class GatewayEventReads(
    private val prefix: String,
    private val client: GatewayClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val clock: Clock,
    private val quoteWaitMs: Long,
) : DirectEventVenue {
    private val symbols = GatewaySymbols(prefix)

    override fun markets(filter: MarketFilter): List<DirectMarket> {
        val filters =
            listOfNotNull(
                "kind" to "event",
                filter.category?.let { "category" to it },
                filter.family?.let { "series" to it.removePrefix(prefix) },
                ("status" to "open").takeIf { filter.openOnly },
            )
        return markets(client.instrumentPages(filters).instruments).sortedBy { it.symbol }
    }

    override fun chain(
        root: String,
        eventOffset: Int,
    ): DirectChain? {
        val series = root.removePrefix(prefix)
        val listed = client.instrumentPages(listOf("kind" to "event", "series" to series)).instruments
        val markets = markets(listed).associateBy { it.symbol }
        val contracts = markets.values.map { FamilyContract(it.symbol, it.terms) }
        val event = OpenEvents(contracts).at(clock.now()).getOrNull(eventOffset) ?: return null
        val codes = listed.associate { symbols.qkt(it.code) to it.code }
        val wanted = event.contracts.map { codes.getValue(it.symbol) }.toSet()
        val quotes = gatewayEventQuotes(baseUrl, apiKey, series, wanted, quoteWaitMs)
        val quoted =
            event.contracts.map { c ->
                val quote = quotes[codes.getValue(c.symbol)]
                val bid = quote?.bid?.let(::BigDecimal)
                DirectChainContract(markets.getValue(c.symbol), bid, quote?.ask?.let(::BigDecimal))
            }
        return DirectChain(event.event, event.closeAtMs, quoted)
    }

    override fun follow(slot: SlotSymbol): String {
        val chain =
            requireNotNull(chain(slot.root, slot.eventOffset)) {
                "${slot.symbol}: ${slot.root} has no ${slot.event} event open now"
            }
        val members = chain.contracts.map { FamilyContract(it.market.symbol, it.market.terms) }
        val event = FamilyEvent(chain.event, chain.closeAtMs, members)
        val quotes =
            chain.contracts
                .filter { it.market.trading == FULL }
                .associate { it.market.symbol to Tick(it.market.symbol, BigDecimal.ZERO, 0L, null, it.bid, it.ask) }
        return requireNotNull(SlotPick.pickIn(event, slot.rule, quotes::get)) {
            "${slot.symbol}: no tradable contract of ${chain.event} has a two-sided quote to pick by"
        }
    }

    override fun refusal(qktSymbol: String): String? {
        val instrument = client.instruments().firstOrNull { symbols.qkt(it.code) == qktSymbol && it.kind == "event" }
        return when (instrument?.trading) {
            "disabled" -> "$qktSymbol is paused by the venue (trading: disabled); nothing was sent"
            "close_only" -> "$qktSymbol only closes out (trading: close_only); close a holding with qkt bot close"
            else -> null
        }
    }

    private fun markets(instruments: List<WireInstrument>): List<DirectMarket> =
        instruments.mapNotNull { i ->
            val symbol = symbols.qkt(i.code)
            runCatching { i.eventSpec(symbol).meta.derivative as EventTerms }.getOrNull()?.let {
                DirectMarket(symbol, it, i.category, i.trading ?: FULL)
            }
        }

    private companion object {
        const val FULL = "full"
    }
}
