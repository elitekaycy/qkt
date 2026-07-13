package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.daemon.StateDir
import com.qkt.common.SystemClock
import com.qkt.trade.BotGateway
import com.qkt.trade.BotPlaceResult
import com.qkt.trade.BotTrail

/**
 * `qkt bot modify BROKER:SYMBOL --ticket N [--sl <px>] [--tp <px>]` — moves an open
 * position's protective levels (absolute prices). The venue clears an omitted level,
 * so the current SL/TP is read first and preserved unless explicitly overridden.
 */
class BotModifyCommand(
    private val args: Args,
) {
    fun run(): Int {
        val json = args.flag("json")
        return botRun(json) { execute(json) }
    }

    private fun execute(json: Boolean): Int {
        val symbol = args.requirePositional(0, "BROKER:SYMBOL")
        val ticket = args.requireOption("ticket").toLongOrNull() ?: error("invalid --ticket")
        val slArg = args.option("sl")?.let { it.toBigDecimalOrNull() ?: error("invalid --sl '$it'") }
        val tpArg = args.option("tp")?.let { it.toBigDecimalOrNull() ?: error("invalid --tp '$it'") }
        require(slArg != null || tpArg != null) { "pass --sl and/or --tp" }
        val cfg = botConfig(args)
        val gateway = BotGateway.forSymbol(cfg, symbol)
        val positions = gateway.positions() ?: error("venue positions read failed")
        val pos =
            positions.firstOrNull { it.ticket == ticket }
                ?: error("no open position with ticket $ticket")
        val sl = slArg ?: pos.sl.takeIf { it.signum() > 0 }
        val tp = tpArg ?: pos.tp.takeIf { it.signum() > 0 }
        val result = gateway.modify(ticket, sl, tp)
        recordBotEvent(
            args,
            cfg,
            "bot.modify",
            mapOf(
                "symbol" to symbol,
                "ticket" to ticket.toString(),
                "sl" to sl?.toPlainString(),
                "tp" to tp?.toPlainString(),
                "ok" to result.ok.toString(),
                "reason" to result.error,
            ),
        )
        return emitBotResult(json, result, "MODIFIED ticket=$ticket sl=${sl ?: "-"} tp=${tp ?: "-"}")
    }
}

/**
 * `qkt bot cancel BROKER:SYMBOL --order N | --all` — cancels resting pending orders
 * by ticket, or every pending order on the symbol with `--all`.
 */
class BotCancelCommand(
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
        val targets =
            args.option("order")?.let { listOf(it.toLongOrNull() ?: error("invalid --order '$it'")) }
                ?: if (args.flag("all")) {
                    val brokerSym = gateway.brokerSymbol(symbol)
                    val pending = gateway.pendingOrders() ?: error("venue pending-orders read failed")
                    pending.filter { it.symbol == brokerSym }.map { it.ticket }.ifEmpty {
                        error("no pending orders for $symbol")
                    }
                } else {
                    error("pass --order <ticket> or --all")
                }
        val results = targets.map { it to gateway.cancel(it) }
        results.forEach { (t, ok) ->
            recordBotEvent(
                args,
                cfg,
                "bot.cancel",
                mapOf("symbol" to symbol, "ticket" to t.toString(), "ok" to ok.toString()),
            )
        }
        val allOk = results.all { it.second }
        if (json) {
            println(jsonArr(results.map { (t, ok) -> jsonObj("ok" to ok, "ticket" to t) }))
        } else {
            results.forEach { (t, ok) ->
                if (ok) println("CANCELLED ticket=$t") else System.err.println("CANCEL FAILED ticket=$t")
            }
        }
        return if (allOk) ExitCodes.SUCCESS else ExitCodes.USER_ERROR
    }
}

private fun recordBotEvent(
    args: Args,
    cfg: com.qkt.cli.Config,
    kind: String,
    fields: Map<String, String?>,
) {
    BotTrail(StateDir.resolve(args.option("state-dir")).stateRoot, cfg.insights, SystemClock()).use {
        it.recordEvent(args.option("as") ?: "manual", kind, fields)
    }
}

private fun emitBotResult(
    json: Boolean,
    result: BotPlaceResult,
    humanOk: String,
): Int {
    if (json) {
        println(
            jsonObj(
                "ok" to result.ok,
                "retcode" to result.retcode,
                "error" to result.error,
            ),
        )
    } else if (result.ok) {
        println(humanOk)
    } else {
        System.err.println("FAILED: ${result.error} (retcode ${result.retcode})")
    }
    return if (result.ok) ExitCodes.SUCCESS else ExitCodes.USER_ERROR
}
