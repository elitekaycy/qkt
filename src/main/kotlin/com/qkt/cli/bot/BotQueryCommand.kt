package com.qkt.cli.bot

import com.qkt.candles.TimeWindow
import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.common.SystemClock
import com.qkt.connectivity.DirectVenue
import com.qkt.trade.venue.BotVenues
import com.qkt.trade.venue.idJson
import java.time.Instant

/**
 * Read-only `qkt bot` verbs — venue truth for an AI (or human) to analyze before
 * acting: `account`, `positions [SYMBOL]`, `orders [SYMBOL]`, `quote SYMBOL`,
 * `bars SYMBOL --tf <tf> [--count N]`, `history [--since <iso|Nd>]`. All print a
 * single JSON document with `--json`; a failed venue read is an error, never empty.
 */
class BotQueryCommand(
    private val args: Args,
    private val verb: String,
) {
    fun run(): Int {
        val json = args.flag("json")
        return botRun(json) {
            when (verb) {
                "account" -> account(json)
                "positions" -> positions(json)
                "orders" -> orders(json)
                "quote" -> botQuote(args, json)
                "bars" -> bars(json)
                "history" -> history(json)
                else -> error("unknown query verb '$verb'")
            }
        }
    }

    private fun <T> venue(
        symbol: String?,
        read: (DirectVenue) -> T,
    ): T {
        val cfg = botConfig(args)
        val opened =
            if (symbol !=
                null
            ) {
                BotVenues.forSymbol(cfg, symbol)
            } else {
                BotVenues.forBroker(cfg, args.option("broker"))
            }
        return opened.use { read(it.venue) }
    }

    private fun account(json: Boolean): Int {
        val a = venue(null) { it.account() } ?: error("venue account read failed")
        val doc =
            jsonObj(
                "ok" to true,
                "equity" to a.equity,
                "balance" to a.balance,
                "currency" to a.currency,
                "margin" to a.margin,
                "freeMargin" to a.freeMargin,
                "marginLevel" to a.marginLevel,
                "openProfit" to a.openProfit,
                "leverage" to a.leverage,
                "login" to idJson(a.login),
                "server" to a.server,
                "hedging" to a.hedging,
            )
        if (json) {
            println(doc)
        } else {
            println(
                "equity=${a.equity} balance=${a.balance} ${a.currency} margin=${a.margin ?: "-"} free=${a.freeMargin ?: "-"} level=${a.marginLevel ?: "-"} leverage=1:${a.leverage} (${a.server} #${a.login})",
            )
        }
        return ExitCodes.SUCCESS
    }

    private fun positions(json: Boolean): Int {
        val symbol = args.positional(0)
        val rows =
            venue(symbol) { v ->
                val all = v.positions() ?: error("venue positions read failed")
                val matching = if (symbol != null) all.filter { it.symbol == v.venueSymbol(symbol) } else all
                matching.map { it to v.qktSymbol(it.symbol) }
            }
        if (json) {
            println(jsonArr(rows.map { (p, qkt) -> p.doc(qkt) }))
        } else if (rows.isEmpty()) {
            println("no open positions")
        } else {
            rows.forEach { (p, qkt) ->
                println("${p.ticket} $qkt ${p.side} ${p.lots} @ ${p.entry} sl=${p.sl} tp=${p.tp} pnl=${p.profit}")
            }
        }
        return ExitCodes.SUCCESS
    }

    private fun orders(json: Boolean): Int {
        val symbol = args.positional(0)
        val rows =
            venue(symbol) { v ->
                val all = v.pendingOrders() ?: error("venue pending-orders read failed")
                val matching = if (symbol != null) all.filter { it.symbol == v.venueSymbol(symbol) } else all
                matching.map { it to v.qktSymbol(it.symbol) }
            }
        if (json) {
            println(jsonArr(rows.map { (o, qkt) -> o.doc(qkt) }))
        } else if (rows.isEmpty()) {
            println("no pending orders")
        } else {
            rows.forEach { (o, qkt) ->
                println("${o.ticket} $qkt ${o.type} ${o.lots} @ ${o.price} sl=${o.sl} tp=${o.tp}")
            }
        }
        return ExitCodes.SUCCESS
    }

    private fun bars(json: Boolean): Int {
        val symbol = args.requirePositional(0, "BROKER:SYMBOL")
        val window = TimeWindow.parse(args.requireOption("tf"))
        val count = args.option("count")?.toIntOrNull() ?: 100
        val rows = venue(symbol) { it.bars(symbol, window, count, SystemClock().now()) }
        val docs =
            rows.map {
                jsonObj(
                    "t" to it.startTime,
                    "o" to it.open,
                    "h" to it.high,
                    "l" to it.low,
                    "c" to it.close,
                    "v" to it.volume,
                )
            }
        if (json) {
            println(jsonArr(docs))
        } else {
            rows.forEach {
                println(
                    "${Instant.ofEpochMilli(it.startTime)} o=${it.open} h=${it.high} l=${it.low} c=${it.close}",
                )
            }
        }
        return ExitCodes.SUCCESS
    }

    private fun history(json: Boolean): Int {
        val now = SystemClock().now()
        val since = parseSince(args.option("since"), now)
        val deals =
            venue(null) { v -> v.deals(since, now)?.map { it to v.qktSymbol(it.symbol) } }
                ?: error("venue deal-history read failed")
        if (json) {
            println(jsonArr(deals.map { (d, qkt) -> d.doc(qkt) }))
        } else if (deals.isEmpty()) {
            println("no deals since ${Instant.ofEpochMilli(since)}")
        } else {
            deals.forEach { (d, qkt) ->
                println("${Instant.ofEpochMilli(d.timeMs)} $qkt ${d.side} ${d.lots} @ ${d.price} pnl=${d.profit}")
            }
        }
        return ExitCodes.SUCCESS
    }
}
