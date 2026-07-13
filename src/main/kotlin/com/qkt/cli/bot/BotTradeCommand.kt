package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.BuildInfo
import com.qkt.cli.ExitCodes
import com.qkt.cli.daemon.StateDir
import com.qkt.common.Side
import com.qkt.common.SystemClock
import com.qkt.trade.BotGateway
import com.qkt.trade.BotIntent
import com.qkt.trade.BotTif
import com.qkt.trade.BotTrail
import com.qkt.trade.ExitSpec
import com.qkt.trade.compileBotAction
import com.qkt.trade.parseBotStrategy
import com.qkt.trade.renderBotStrategy
import java.time.Instant

/**
 * `qkt bot buy|sell` — renders the arguments to canonical DSL, compiles against the
 * live quote, submits synchronously, and journals/egresses the whole trail. Exit 0
 * only on a venue accept.
 */
class BotTradeCommand(
    private val args: Args,
    private val side: Side,
) {
    fun run(): Int {
        val json = args.flag("json")
        return botRun(json) { execute(json) }
    }

    private fun execute(json: Boolean): Int {
        val intent = parseBotIntent(args, side)
        val source = renderBotStrategy(intent)
        val action = parseBotStrategy(source)
        val asName = args.option("as") ?: "manual"
        if (args.flag("dry-run")) {
            if (json) {
                println(
                    jsonObj(
                        "ok" to true,
                        "dryRun" to true,
                        "canonicalDsl" to source,
                        "sha256" to action.sha256,
                        "symbol" to action.qktSymbol,
                        "side" to side.name,
                        "as" to asName,
                    ),
                )
            } else {
                println(source)
                println("sha256: ${action.sha256} (dry run — nothing submitted)")
            }
            return ExitCodes.SUCCESS
        }
        val cfg = botConfig(args)
        val gateway = BotGateway.forSymbol(cfg, intent.qktSymbol)
        val ctx = gateway.quoteContext(intent.qktSymbol, cfg.accountCurrency)
        val clock = SystemClock()
        val ts = clock.now()
        val order = compileBotAction(action, ctx, id = "bot-$asName-$ts", timestamp = ts, strategyId = asName)
        val result =
            BotTrail(StateDir.resolve(args.option("state-dir")).stateRoot, cfg.insights, clock).use { trail ->
                trail.recordCommand(asName, action, args.tokens)
                trail.recordSubmit(asName, order)
                gateway.place(order).also { trail.recordResult(asName, order, it) }
            }
        if (json) {
            println(
                jsonObj(
                    "ok" to result.ok,
                    "ticket" to result.ticket,
                    "deal" to result.deal,
                    "fillPrice" to result.price,
                    "retcode" to result.retcode,
                    "error" to result.error,
                    "symbol" to action.qktSymbol,
                    "side" to side.name,
                    "lots" to order.request.quantity,
                    "sl" to order.stopLoss,
                    "tp" to order.takeProfit,
                    "as" to asName,
                    "canonicalDsl" to source,
                    "sha256" to action.sha256,
                    "qktVersion" to BuildInfo.VERSION,
                ),
            )
        } else if (result.ok) {
            println(
                "ACCEPTED ${action.qktSymbol} ${side.name} ${order.request.quantity.toPlainString()} " +
                    "ticket=${result.ticket} deal=${result.deal} price=${result.price.toPlainString()}" +
                    (order.stopLoss?.let { " sl=${it.toPlainString()}" } ?: "") +
                    (order.takeProfit?.let { " tp=${it.toPlainString()}" } ?: ""),
            )
        } else {
            System.err.println("REJECTED: ${result.error} (retcode ${result.retcode})")
        }
        return if (result.ok) ExitCodes.SUCCESS else ExitCodes.USER_ERROR
    }
}

/** Parses `qkt bot buy/sell` arguments into a validated [BotIntent]. */
internal fun parseBotIntent(
    args: Args,
    side: Side,
): BotIntent {
    val first = args.requirePositional(0, "lots or BROKER:SYMBOL")
    val lots = first.toBigDecimalOrNull()
    val symbol = if (lots == null) first else args.requirePositional(1, "BROKER:SYMBOL")
    require(symbol.contains(':')) { "symbol must be BROKER:SYMBOL (e.g. EXNESS:XAUUSD), got '$symbol'" }
    val stopLimit =
        args.option("stop-limit")?.let { spec ->
            val parts = spec.split(':')
            require(parts.size == 2) { "--stop-limit must be <trigger>:<limit>, got '$spec'" }
            (
                parts[0].toBigDecimalOrNull() ?: error("invalid --stop-limit trigger '${parts[0]}'")
            ) to (parts[1].toBigDecimalOrNull() ?: error("invalid --stop-limit limit '${parts[1]}'"))
        }
    val expires = args.option("expires")?.let(::parseExpiry)
    val tif =
        when (args.option("tif")?.uppercase()) {
            null -> if (expires != null) BotTif.GTD else BotTif.GTC
            "GTC" -> BotTif.GTC
            "DAY" -> BotTif.DAY
            "GTD" -> BotTif.GTD
            else -> error("--tif must be gtc, day, or gtd")
        }
    return BotIntent(
        side = side,
        qktSymbol = symbol,
        lots = lots,
        sizingDsl = args.option("sizing"),
        limitPrice = args.option("limit")?.let { it.toBigDecimalOrNull() ?: error("invalid --limit '$it'") },
        stopPrice =
            stopLimit?.first
                ?: args.option("stop")?.let { it.toBigDecimalOrNull() ?: error("invalid --stop '$it'") },
        stopLimitPrice = stopLimit?.second,
        sl = args.option("sl")?.let { ExitSpec.parse(it, allowRr = false) },
        tp = args.option("tp")?.let { ExitSpec.parse(it, allowRr = true) },
        tif = tif,
        expiresAtMs = expires,
    )
}

/** Accepts an ISO-8601 instant (`2026-07-14T00:00:00Z`) or raw UTC epoch millis. */
internal fun parseExpiry(raw: String): Long =
    raw.toLongOrNull()
        ?: runCatching { Instant.parse(raw).toEpochMilli() }.getOrElse {
            error("--expires must be ISO-8601 (e.g. 2026-07-14T00:00:00Z) or epoch millis, got '$raw'")
        }
