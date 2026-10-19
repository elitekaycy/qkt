package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.common.Side
import com.qkt.trade.renderBotAction
import com.qkt.trade.renderBotStrategy

/**
 * Session-routed implementations of the bot verbs. Each takes an already-resolved
 * [BotSessionClient]; when no session is running the verbs never reach here and the
 * direct venue path runs unchanged.
 */
internal object BotSessionVerbs {
    /** `bot next BROKER:SYMBOL` — pull the next closed bar (advances a backtest's clock). */
    fun next(
        client: BotSessionClient,
        args: Args,
    ): Int {
        val symbol = args.requirePositional(0, "BROKER:SYMBOL")
        val tf = args.option("tf")?.let { ""","tf":${jsonString(it)}""" }.orEmpty()
        println(client.post("/next", """{"symbol":${jsonString(symbol)}$tf}"""))
        return ExitCodes.SUCCESS
    }

    /** `bot buy|sell` — render the canonical DSL and submit it as a session intent. */
    fun trade(
        client: BotSessionClient,
        args: Args,
        side: Side,
    ): Int {
        val intent = parseBotIntent(args, side)
        val source = renderBotStrategy(intent)
        val identity = args.option("as") ?: "manual"
        if (args.flag("dry-run")) {
            println(jsonObj("ok" to true, "dryRun" to true, "canonicalDsl" to source, "as" to identity))
            return ExitCodes.SUCCESS
        }
        val body =
            """{"identity":${jsonString(identity)},"source":${jsonString(source)}}"""
        println(client.post("/intent", body))
        return ExitCodes.SUCCESS
    }

    fun quote(
        client: BotSessionClient,
        args: Args,
    ): Int {
        val symbol = args.requirePositional(0, "BROKER:SYMBOL")
        println(client.get("/quote?symbol=${java.net.URLEncoder.encode(symbol, Charsets.UTF_8)}"))
        return ExitCodes.SUCCESS
    }

    fun bars(
        client: BotSessionClient,
        args: Args,
    ): Int {
        val symbol = args.requirePositional(0, "BROKER:SYMBOL")
        val count = args.option("count")?.toIntOrNull() ?: 100
        println(
            client.get(
                "/bars?symbol=${java.net.URLEncoder.encode(symbol, Charsets.UTF_8)}&count=$count" +
                    (args.option("tf")?.let { "&tf=$it" } ?: ""),
            ),
        )
        return ExitCodes.SUCCESS
    }

    fun positions(client: BotSessionClient): Int {
        println(client.get("/positions"))
        return ExitCodes.SUCCESS
    }

    fun account(client: BotSessionClient): Int {
        println(client.get("/status"))
        return ExitCodes.SUCCESS
    }

    /** `bot close SYMBOL` closes this identity's position on it through the engine; `--all` closes everything. */
    fun close(
        client: BotSessionClient,
        args: Args,
    ): Int {
        require(args.option("partial") == null) {
            "in a session, reduce a position with the opposite order (`qkt bot sell <qty> <SYMBOL>` on a netting " +
                "book); on a hedging book that opens a leg, so close all of it"
        }
        require(args.option("ticket") == null) { "in a session, close by symbol; the engine closes the legs by ticket" }
        val symbol = args.positional(0)
        return when {
            symbol != null -> action(client, args, mapOf("x" to symbol), "CLOSE x")
            args.flag("all") -> action(client, args, mapOf("x" to firstSymbol(client)), "CLOSE_ALL")
            else -> error("pass BROKER:SYMBOL, or --all to close every position of the identity")
        }
    }

    /** `bot cancel SYMBOL` cancels this identity's working orders on it; `--all` (no symbol) every one. */
    fun cancel(
        client: BotSessionClient,
        args: Args,
    ): Int {
        require(args.option("order") == null) { "in a session, cancel by symbol: qkt bot cancel <SYMBOL>" }
        val symbol = args.positional(0)
        return when {
            symbol != null -> action(client, args, mapOf("x" to symbol), "CANCEL x")
            args.flag("all") -> action(client, args, mapOf("x" to firstSymbol(client)), "CANCEL_ALL")
            else -> error("pass BROKER:SYMBOL, or --all to cancel every working order of the identity")
        }
    }

    /** `bot submit '<action>' --symbol SYMBOL` (alias `x`) or `--symbols a=SYM,b=SYM`: any DSL action. */
    fun submit(
        client: BotSessionClient,
        args: Args,
    ): Int {
        val text = args.requirePositional(0, "'<DSL action>'")
        val streams =
            args.option("symbols")?.split(',')?.associate { pair ->
                pair.substringBefore('=').trim() to pair.substringAfter('=').trim()
            }
                ?: mapOf(
                    "x" to
                        (args.option("symbol") ?: error("pass --symbol BROKER:SYMBOL (alias x) or --symbols a=SYM,..")),
                )
        return action(client, args, streams, text)
    }

    fun refuseModify(): Int =
        error(
            "a session holds its stops in the engine, so they are set with the entry: --sl trail:<d>[@<mfe>] trails, " +
                "`qkt bot submit` takes any bracket (STOP LOSS BY 5 STEP TO BREAKEVEN AFTER MFE >= 10), or close and re-enter",
        )

    fun get(
        client: BotSessionClient,
        path: String,
    ): Int {
        println(client.get(path))
        return ExitCodes.SUCCESS
    }

    private fun action(
        client: BotSessionClient,
        args: Args,
        streams: Map<String, String>,
        action: String,
    ): Int {
        val source = renderBotAction(streams, action)
        val identity = args.option("as") ?: "manual"
        if (args.flag("dry-run")) {
            println(jsonObj("ok" to true, "dryRun" to true, "canonicalDsl" to source, "as" to identity))
            return ExitCodes.SUCCESS
        }
        println(client.post("/intent", """{"identity":${jsonString(identity)},"source":${jsonString(source)}}"""))
        return ExitCodes.SUCCESS
    }

    /** A symbol the session declares, for actions that name none (`CLOSE_ALL` still declares a stream). */
    private fun firstSymbol(client: BotSessionClient): String =
        Regex("\"symbols\":\\[\"([^\"]+)\"").find(client.get("/status"))?.groupValues?.get(1)
            ?: error("the session reports no symbols")
}
