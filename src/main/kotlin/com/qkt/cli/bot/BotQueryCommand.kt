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
                "quote" -> quote(json)
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
                if (symbol != null) all.filter { it.symbol == v.venueSymbol(symbol) } else all
            }
        val docs =
            rows.map {
                jsonObj(
                    "ticket" to idJson(it.ticket),
                    "symbol" to it.symbol,
                    "side" to it.side,
                    "lots" to it.lots,
                    "entry" to it.entry,
                    "current" to it.current,
                    "sl" to it.sl,
                    "tp" to it.tp,
                    "profit" to it.profit,
                    "swap" to it.swap,
                    "openedAtMs" to it.openedAtMs,
                    "comment" to it.comment,
                )
            }
        if (json) {
            println(jsonArr(docs))
        } else if (rows.isEmpty()) {
            println("no open positions")
        } else {
            rows.forEach {
                println(
                    "${it.ticket} ${it.symbol} ${it.side} ${it.lots} @ ${it.entry} sl=${it.sl} tp=${it.tp} pnl=${it.profit}",
                )
            }
        }
        return ExitCodes.SUCCESS
    }

    private fun orders(json: Boolean): Int {
        val symbol = args.positional(0)
        val rows =
            venue(symbol) { v ->
                val all = v.pendingOrders() ?: error("venue pending-orders read failed")
                if (symbol != null) all.filter { it.symbol == v.venueSymbol(symbol) } else all
            }
        val docs =
            rows.map {
                jsonObj(
                    "ticket" to idJson(it.ticket),
                    "symbol" to it.symbol,
                    "type" to it.type,
                    "lots" to it.lots,
                    "price" to it.price,
                    "sl" to it.sl,
                    "tp" to it.tp,
                    "expiresAt" to it.expiresAt,
                )
            }
        if (json) {
            println(jsonArr(docs))
        } else if (rows.isEmpty()) {
            println("no pending orders")
        } else {
            rows.forEach {
                println(
                    "${it.ticket} ${it.symbol} ${it.type} ${it.lots} @ ${it.price} sl=${it.sl} tp=${it.tp}",
                )
            }
        }
        return ExitCodes.SUCCESS
    }

    private fun quote(json: Boolean): Int {
        val symbol = args.requirePositional(0, "BROKER:SYMBOL")
        val t = venue(symbol) { it.tick(symbol) } ?: error("venue did not return a quote for $symbol")
        val spread = t.ask.subtract(t.bid)
        if (json) {
            println(
                jsonObj("symbol" to symbol, "bid" to t.bid, "ask" to t.ask, "spread" to spread, "timeMs" to t.timeMs),
            )
        } else {
            println("$symbol bid=${t.bid} ask=${t.ask} spread=${spread.toPlainString()}")
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
        val deals = venue(null) { it.deals(since, now) } ?: error("venue deal-history read failed")
        val docs =
            deals.map {
                jsonObj(
                    "dealTicket" to idJson(it.dealTicket),
                    "positionTicket" to it.positionTicket?.let(::idJson),
                    "symbol" to it.symbol,
                    "side" to it.side,
                    "entry" to it.entry,
                    "lots" to it.lots,
                    "price" to it.price,
                    "profit" to it.profit,
                    "commission" to it.commission,
                    "swap" to it.swap,
                    "fee" to it.fee,
                    "timeMs" to it.timeMs,
                    "comment" to it.comment,
                )
            }
        if (json) {
            println(jsonArr(docs))
        } else if (deals.isEmpty()) {
            println("no deals since ${Instant.ofEpochMilli(since)}")
        } else {
            deals.forEach {
                println(
                    "${Instant.ofEpochMilli(
                        it.timeMs,
                    )} ${it.symbol} ${it.side} ${it.lots} @ ${it.price} pnl=${it.profit}",
                )
            }
        }
        return ExitCodes.SUCCESS
    }
}
