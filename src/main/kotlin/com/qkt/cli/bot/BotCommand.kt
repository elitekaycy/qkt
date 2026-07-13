package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.Config
import com.qkt.cli.ExitCodes
import com.qkt.common.Side

/**
 * The `qkt bot` command group — a one-shot manual/AI trading surface. Trade verbs
 * (buy/sell/close/modify/cancel) fire a single venue call and exit; query verbs
 * (account/positions/orders/quote/bars/history/eval) read venue truth. Every verb
 * accepts `--config` and `--json`; trade verbs add `--as <name>` (attribution,
 * default `manual`) and `--dry-run`.
 */
class BotCommand(
    private val args: Args,
) {
    fun run(): Int {
        val verb = args.firstNonOption() ?: return help()
        val sub = Args(args.tokens.drop(1).toTypedArray())
        return when (verb) {
            "buy" -> BotTradeCommand(sub, Side.BUY).run()
            "sell" -> BotTradeCommand(sub, Side.SELL).run()
            "close" -> BotCloseCommand(sub).run()
            "modify" -> BotModifyCommand(sub).run()
            "cancel" -> BotCancelCommand(sub).run()
            "account", "positions", "orders", "quote", "bars", "history" ->
                BotQueryCommand(sub, verb).run()
            "eval" -> BotEvalCommand(sub).run()
            "help", "--help" -> help()
            else -> {
                System.err.println("qkt bot: unknown verb '$verb' (see qkt bot help)")
                ExitCodes.ARG_ERROR
            }
        }
    }

    private fun help(): Int {
        println(
            """
            qkt bot — one-shot manual/AI trading against a configured MT5 broker

            TRADE (all: --as <name> --json --dry-run --config <path>)
                bot buy  <lots> BROKER:SYMBOL [--sl <spec>] [--tp <spec>]
                bot buy  BROKER:SYMBOL --sizing "<dsl sizing>" ...
                bot sell ... (same shape)
                    entry: --limit <px> | --stop <px> | --stop-limit <trigger>:<limit>
                    tif:   --tif gtc|day|gtd [--expires <iso8601|epoch-ms>]
                    exits: <spec> = 2610 | at:2610 | by:30 | pct:1.5 | rr:2 (tp only)
                bot close  BROKER:SYMBOL [--ticket N] [--partial <lots>] [--all]
                bot modify BROKER:SYMBOL --ticket N [--sl <px>] [--tp <px>]
                bot cancel BROKER:SYMBOL --order N | --all

            QUERY (all: --json --config <path>; --broker <name> when no symbol)
                bot account                      equity, balance, margin, currency
                bot positions [BROKER:SYMBOL]    open venue positions
                bot orders    [BROKER:SYMBOL]    resting pending orders
                bot quote     BROKER:SYMBOL      bid/ask
                bot bars      BROKER:SYMBOL --tf 1h [--count 100]
                bot history   [--since <iso|Nd>] closed deals
                bot eval "ema(21)" BROKER:SYMBOL --tf 1h [--count 500]

            Engine-managed shapes (trailing, stack, latch) are rejected — deploy a .qkt.
            """.trimIndent(),
        )
        return ExitCodes.SUCCESS
    }
}

/** Loads the resolved config for a bot verb (`--config` flag, then standard search paths). */
internal fun botConfig(args: Args): Config = Config.load(Config.resolvePath(args.option("config")))

/** Prints a failure as JSON or stderr and returns the user-error exit code. */
internal fun botFail(
    json: Boolean,
    message: String,
): Int {
    if (json) println(jsonObj("ok" to false, "error" to message)) else System.err.println("qkt bot: $message")
    return ExitCodes.USER_ERROR
}

/**
 * Runs [body] with the bot error boundary: argument/state failures surface as a
 * clean `{ok:false,error}` (or stderr line) instead of a stack trace.
 */
internal inline fun botRun(
    json: Boolean,
    body: () -> Int,
): Int =
    try {
        body()
    } catch (e: IllegalArgumentException) {
        botFail(json, e.message ?: "invalid arguments")
    } catch (e: IllegalStateException) {
        botFail(json, e.message ?: "command failed")
    }
