package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.daemon.StateDir
import com.qkt.common.SystemClock
import com.qkt.trade.BotTrail
import com.qkt.trade.venue.BotVenues
import com.qkt.trade.venue.botOrderId
import com.qkt.trade.venue.idJson
import com.qkt.trade.venue.ownedBy

/**
 * `qkt bot close BROKER:SYMBOL [--ticket N] [--partial <lots>] [--all]` — closes open
 * venue positions by ticket. With one open position on the symbol, no flag is needed;
 * with several, pass `--ticket` or `--all`. `--partial` requires a single target.
 */
class BotCloseCommand(
    private val args: Args,
) {
    fun run(): Int {
        val json = args.flag("json")
        return botRun(json) { execute(json) }
    }

    private fun execute(json: Boolean): Int {
        val symbol = args.requirePositional(0, "BROKER:SYMBOL")
        val cfg = botConfig(args)
        val asName = args.option("as") ?: "manual"
        val clock = SystemClock()
        val results =
            BotVenues.forSymbol(cfg, symbol).use { opened ->
                val venue = opened.venue
                val venueSym = venue.venueSymbol(symbol)
                val positions =
                    venue.positions()
                        ?: error("venue positions read failed; refusing to close on unknown state")
                val matching = positions.filter { it.symbol == venueSym }
                val ticket = args.option("ticket")
                val targets =
                    when {
                        ticket != null ->
                            listOf(
                                matching.firstOrNull { it.ticket == ticket }
                                    ?: error("no open position with ticket $ticket"),
                            )
                        args.flag("all") -> {
                            // --all is the caller's own positions; a deployed strategy's stay unless --any.
                            val mine =
                                if (args.flag("any")) {
                                    matching
                                } else {
                                    matching.filter { ownedBy(asName, it.comment) }
                                }
                            val others =
                                if (matching.isEmpty()) "" else " (${matching.size} held by others; pass --any)"
                            mine.ifEmpty { error("no open positions of '$asName' on $symbol$others") }
                        }
                        matching.size == 1 -> matching
                        matching.isEmpty() -> error("no open positions for $symbol")
                        else ->
                            error(
                                "multiple positions open for $symbol " +
                                    "(tickets ${matching.joinToString { it.ticket }}); pass --ticket or --all",
                            )
                    }
                val partial =
                    args.option("partial")?.let {
                        require(targets.size == 1) { "--partial needs a single target ticket" }
                        it.toBigDecimalOrNull() ?: error("invalid --partial '$it'")
                    }
                BotTrail(StateDir.resolve(args.option("state-dir")).stateRoot, cfg.insights, clock).use { trail ->
                    targets.map { t ->
                        val result = venue.close(t, partial, botOrderId(asName, clock.now()))
                        trail.recordEvent(
                            asName,
                            "bot.close",
                            mapOf(
                                "symbol" to symbol,
                                "ticket" to t.ticket,
                                "partial" to partial?.toPlainString(),
                                "ok" to result.ok.toString(),
                                "deal" to result.deal,
                                "price" to result.price.toPlainString(),
                                "reason" to result.error,
                            ),
                        )
                        t.ticket to result
                    }
                }
            }
        val ok = results.all { it.second.ok }
        if (json) {
            println(
                jsonArr(
                    results.map { (t, r) ->
                        jsonObj(
                            "ok" to r.ok,
                            "ticket" to idJson(t),
                            "deal" to idJson(r.deal),
                            "price" to r.price,
                            "retcode" to r.retcode,
                            "error" to r.error,
                        )
                    },
                ),
            )
        } else {
            results.forEach { (t, r) ->
                if (r.ok) {
                    println("CLOSED ticket=$t deal=${r.deal} price=${r.price.toPlainString()}")
                } else {
                    System.err.println("CLOSE FAILED ticket=$t: ${r.error} (retcode ${r.retcode})")
                }
            }
        }
        return if (ok) ExitCodes.SUCCESS else ExitCodes.USER_ERROR
    }
}
