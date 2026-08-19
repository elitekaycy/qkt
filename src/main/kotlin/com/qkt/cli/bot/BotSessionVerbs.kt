package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.common.Side
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
        println(client.post("/next", """{"symbol":${jsonString(symbol)}}"""))
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
            client.get("/bars?symbol=${java.net.URLEncoder.encode(symbol, Charsets.UTF_8)}&count=$count"),
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
}
