package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.connectivity.DirectChain
import com.qkt.connectivity.DirectEventVenue
import com.qkt.connectivity.DirectMarket
import com.qkt.connectivity.DirectVenue
import com.qkt.connectivity.MarketFilter
import com.qkt.instrument.SlotSelectors
import com.qkt.instrument.SlotSymbol
import com.qkt.trade.venue.BotVenues
import java.time.Instant

/**
 * `qkt bot markets <VENUE> [--category C] [--family F] [--open]`: the venue's event catalog read through its listing
 * filters, as a strategy would see it; `qkt bot chain <VENUE>:<FAMILY>@<front|next>`: every contract of one open
 * event of a family, in strike order, with its quote and terms. Both print one JSON document with `--json`.
 */
class BotEventCommand(
    private val args: Args,
    private val verb: String,
) {
    fun run(): Int {
        val json = args.flag("json")
        return botRun(json) { if (verb == "markets") markets(json) else chain(json) }
    }

    private fun markets(json: Boolean): Int {
        val venue = args.requirePositional(0, "VENUE")
        val filter = MarketFilter(args.option("category"), args.option("family"), args.flag("open"))
        val rows = BotVenues.forBroker(botConfig(args), venue).use { eventVenue(it.venue, venue).markets(filter) }
        if (json) {
            println(jsonArr(rows.map(::marketJson)))
        } else if (rows.isEmpty()) {
            println("no event contracts match")
        } else {
            rows.forEach { println("${it.symbol} ${it.terms.event} \"${it.terms.title.orEmpty()}\" ${it.trading}") }
        }
        return ExitCodes.SUCCESS
    }

    private fun chain(json: Boolean): Int {
        val spec = args.requirePositional(0, "VENUE:FAMILY@<front|next>")
        val root = spec.substringBefore('@')
        val event = spec.substringAfter('@', missingDelimiterValue = "")
        val offset =
            requireNotNull(SlotSelectors.eventOffset(event)?.takeIf { ':' in root }) {
                "chain takes VENUE:FAMILY@<front|next> (e.g. KALSHI:KXINXU@front), got '$spec'"
            }
        val chain =
            BotVenues.forSymbol(botConfig(args), root).use { eventVenue(it.venue, it.broker).chain(root, offset) }
                ?: error("$root has no $event event open now")
        if (json) println(chainJson(chain)) else printChain(chain)
        return ExitCodes.SUCCESS
    }

    private fun printChain(chain: DirectChain) {
        println("${chain.event} closes ${Instant.ofEpochMilli(chain.closeAtMs)}")
        for (c in chain.contracts) {
            val title = c.market.terms.title.orEmpty()
            println("  ${c.market.symbol} \"$title\" bid=${c.bid} ask=${c.ask} ${c.market.trading}")
        }
    }

    private fun chainJson(chain: DirectChain): String {
        val contracts =
            chain.contracts.map { c ->
                marketJson(c.market).dropLast(1) + "," + jsonObj("bid" to c.bid, "ask" to c.ask).drop(1)
            }
        val head = jsonObj("event" to chain.event, "closeAt" to chain.closeAtMs).dropLast(1)
        return "$head,\"contracts\":${jsonArr(contracts)}}"
    }
}

/** [venue] (the account [name]) as one that lists event contracts, or a refusal naming it. */
internal fun eventVenue(
    venue: DirectVenue,
    name: String,
): DirectEventVenue = venue as? DirectEventVenue ?: error("broker $name lists no event contracts")

/** The contract [symbol] names now: the contract an event slot follows on [venue], or [symbol] itself. */
internal fun followedContract(
    venue: DirectVenue,
    name: String,
    symbol: String,
): String = SlotSymbol.parse(symbol)?.let { eventVenue(venue, name).follow(it) } ?: symbol

private fun marketJson(m: DirectMarket): String =
    jsonObj(
        "symbol" to m.symbol,
        "family" to m.terms.root,
        "event" to m.terms.event,
        "title" to m.terms.title,
        "category" to m.category,
        "strikeType" to m.terms.predicate.type.name.lowercase(),
        "floor" to m.terms.predicate.floor,
        "cap" to m.terms.predicate.cap,
        "openAt" to m.terms.openAtMs,
        "closeAt" to m.terms.closeAtMs,
        "expiry" to m.terms.expiryMs,
        "frequency" to m.terms.frequency,
        "takerFeeRate" to m.terms.fee?.takerRate,
        "makerFeeRate" to m.terms.fee?.makerRate,
        "trading" to m.trading,
    )
