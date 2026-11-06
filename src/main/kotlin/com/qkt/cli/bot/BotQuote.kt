package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.trade.venue.BotVenues

/**
 * `qkt bot quote BROKER:SYMBOL`: the venue's bid and ask now. An event slot (`KALSHI:KXINXU@front.p70`) is resolved
 * first to the contract it follows now, which the answer names (`contract`).
 */
internal fun botQuote(
    args: Args,
    json: Boolean,
): Int {
    val symbol = args.requirePositional(0, "BROKER:SYMBOL")
    val (contract, t) =
        BotVenues.forSymbol(botConfig(args), symbol).use { opened ->
            val contract = followedContract(opened.venue, opened.broker, symbol)
            contract to (opened.venue.tick(contract) ?: error("venue did not return a quote for $contract"))
        }
    val spread = t.ask.subtract(t.bid)
    if (json) {
        val fields =
            listOfNotNull(
                "symbol" to symbol,
                ("contract" to contract).takeIf { contract != symbol },
                "bid" to t.bid,
                "ask" to t.ask,
                "spread" to spread,
                "timeMs" to t.timeMs,
            )
        println(jsonObj(*fields.toTypedArray()))
    } else {
        val followed = if (contract == symbol) "" else " ($contract)"
        println("$symbol$followed bid=${t.bid} ask=${t.ask} spread=${spread.toPlainString()}")
    }
    return ExitCodes.SUCCESS
}
