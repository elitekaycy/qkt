package com.qkt.cli.bot

import com.qkt.candles.TimeWindow
import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.common.SystemClock
import com.qkt.trade.BotGateway
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

    private fun gateway(symbol: String?): BotGateway {
        val cfg = botConfig(args)
        return if (symbol !=
            null
        ) {
            BotGateway.forSymbol(cfg, symbol)
        } else {
            BotGateway.forBroker(cfg, args.option("broker"))
        }
    }

    private fun account(json: Boolean): Int {
        val a = gateway(null).account() ?: error("venue account read failed")
        val doc =
            jsonObj(
                "ok" to true,
                "equity" to a.equity,
                "balance" to a.balance,
                "currency" to a.currency,
                "margin" to a.margin,
                "freeMargin" to a.marginFree,
                "marginLevel" to a.marginLevel,
                "openProfit" to a.profit,
                "leverage" to a.leverage,
                "login" to a.login,
                "server" to a.server,
                "hedging" to a.isHedging,
            )
        if (json) {
            println(doc)
        } else {
            println(
                "equity=${a.equity} balance=${a.balance} ${a.currency} margin=${a.margin ?: "-"} free=${a.marginFree ?: "-"} level=${a.marginLevel ?: "-"} leverage=1:${a.leverage} (${a.server} #${a.login})",
            )
        }
        return ExitCodes.SUCCESS
    }

    private fun positions(json: Boolean): Int {
        val symbol = args.positional(0)
        val gw = gateway(symbol)
        val all = gw.positions() ?: error("venue positions read failed")
        val rows = if (symbol != null) all.filter { it.symbol == gw.brokerSymbol(symbol) } else all
        val docs =
            rows.map {
                jsonObj(
                    "ticket" to it.ticket,
                    "symbol" to it.symbol,
                    "side" to if (it.type == 0) "BUY" else "SELL",
                    "lots" to it.volume,
                    "entry" to it.priceOpen,
                    "current" to it.priceCurrent,
                    "sl" to it.sl,
                    "tp" to it.tp,
                    "profit" to it.profit,
                    "swap" to it.swap,
                    "openedAtMs" to it.openTime,
                    "comment" to it.comment,
                )
            }
        if (json) {
            println(jsonArr(docs))
        } else if (rows.isEmpty()) {
            println("no open positions")
        } else {
            rows.forEach {
                val side = if (it.type == 0) "BUY" else "SELL"
                println(
                    "${it.ticket} ${it.symbol} $side ${it.volume} @ ${it.priceOpen} sl=${it.sl} tp=${it.tp} pnl=${it.profit}",
                )
            }
        }
        return ExitCodes.SUCCESS
    }

    private fun orders(json: Boolean): Int {
        val symbol = args.positional(0)
        val gw = gateway(symbol)
        val all = gw.pendingOrders() ?: error("venue pending-orders read failed")
        val rows = if (symbol != null) all.filter { it.symbol == gw.brokerSymbol(symbol) } else all
        val docs =
            rows.map {
                jsonObj(
                    "ticket" to it.ticket,
                    "symbol" to it.symbol,
                    "type" to it.type,
                    "lots" to it.volume,
                    "price" to it.priceOpen,
                    "sl" to it.sl,
                    "tp" to it.tp,
                    "expiresAt" to it.timeExpiration,
                )
            }
        if (json) {
            println(jsonArr(docs))
        } else if (rows.isEmpty()) {
            println("no pending orders")
        } else {
            rows.forEach {
                println(
                    "${it.ticket} ${it.symbol} ${it.type} ${it.volume} @ ${it.priceOpen} sl=${it.sl} tp=${it.tp}",
                )
            }
        }
        return ExitCodes.SUCCESS
    }

    private fun quote(json: Boolean): Int {
        val symbol = args.requirePositional(0, "BROKER:SYMBOL")
        val t = gateway(symbol).tick(symbol) ?: error("venue did not return a quote for $symbol")
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
        val rows = gateway(symbol).bars(symbol, window, count, SystemClock().now())
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
        val deals = gateway(null).deals(since, now) ?: error("venue deal-history read failed")
        val docs =
            deals.map {
                jsonObj(
                    "dealTicket" to it.ticket,
                    "positionTicket" to it.positionTicket,
                    "symbol" to it.symbol,
                    "side" to if (it.type == 0) "BUY" else "SELL",
                    "entry" to if (it.entry == 0) "IN" else "OUT",
                    "lots" to it.volume,
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
                    )} ${it.symbol} ${if (it.type == 0) "BUY" else "SELL"} ${it.volume} @ ${it.price} pnl=${it.profit}",
                )
            }
        }
        return ExitCodes.SUCCESS
    }
}

/** `--since` accepts `Nd` (days back), an ISO instant, or epoch millis; default 7 days. */
internal fun parseSince(
    raw: String?,
    nowMs: Long,
): Long =
    when {
        raw == null -> nowMs - 7L * 86_400_000L
        raw.matches(Regex("\\d+d")) -> nowMs - raw.dropLast(1).toLong() * 86_400_000L
        raw.toLongOrNull() != null -> raw.toLong()
        else ->
            runCatching { Instant.parse(raw).toEpochMilli() }.getOrElse {
                error("--since must be Nd, ISO-8601, or epoch millis; got '$raw'")
            }
    }
