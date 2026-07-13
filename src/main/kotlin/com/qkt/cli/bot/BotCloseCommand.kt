package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.daemon.StateDir
import com.qkt.common.SystemClock
import com.qkt.trade.BotGateway
import com.qkt.trade.BotTrail

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
        val gateway = BotGateway.forSymbol(cfg, symbol)
        val brokerSym = gateway.brokerSymbol(symbol)
        val positions =
            gateway.positions()
                ?: error("venue positions read failed; refusing to close on unknown state")
        val matching = positions.filter { it.symbol == brokerSym }
        val ticket = args.option("ticket")?.let { it.toLongOrNull() ?: error("invalid --ticket '$it'") }
        val targets =
            when {
                ticket != null -> listOf(ticket)
                args.flag("all") -> matching.map { it.ticket }
                matching.size == 1 -> listOf(matching.single().ticket)
                matching.isEmpty() -> error("no open positions for $symbol")
                else ->
                    error(
                        "multiple positions open for $symbol " +
                            "(tickets ${matching.joinToString { it.ticket.toString() }}); pass --ticket or --all",
                    )
            }
        val partial =
            args.option("partial")?.let {
                require(targets.size == 1) { "--partial needs a single target ticket" }
                it.toBigDecimalOrNull() ?: error("invalid --partial '$it'")
            }
        val asName = args.option("as") ?: "manual"
        val clock = SystemClock()
        val results =
            BotTrail(StateDir.resolve(args.option("state-dir")).stateRoot, cfg.insights, clock).use { trail ->
                targets.map { t ->
                    val result = gateway.close(t, partial)
                    trail.recordEvent(
                        asName,
                        "bot.close",
                        mapOf(
                            "symbol" to symbol,
                            "ticket" to t.toString(),
                            "partial" to partial?.toPlainString(),
                            "ok" to result.ok.toString(),
                            "deal" to result.deal.toString(),
                            "price" to result.price.toPlainString(),
                            "reason" to result.error,
                        ),
                    )
                    t to result
                }
            }
        val ok = results.all { it.second.ok }
        if (json) {
            println(
                jsonArr(
                    results.map { (t, r) ->
                        jsonObj(
                            "ok" to r.ok,
                            "ticket" to t,
                            "deal" to r.deal,
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
