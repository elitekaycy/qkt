package com.qkt.trade

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.Day
import com.qkt.dsl.ast.Gtc
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.TifAst
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Compiles a parsed [BotAction] into a [CompiledBotOrder] using point-in-time venue
 * facts. This is the one-shot counterpart of the strategy `ActionCompiler`: it accepts
 * only the literal subset of the action grammar (numeric sizing and exit prices) and
 * rejects, fail-closed, every shape that needs a persistent engine to manage —
 * trailing entries, armed-trail stops, STACK/OCO/ON FILL.
 *
 * Sided pricing: BUY resolves market entry and exits against [BotQuoteContext.ask],
 * SELL against [BotQuoteContext.bid]; pending entries price exits from their own
 * entry price.
 */
fun compileBotAction(
    bot: BotAction,
    ctx: BotQuoteContext,
    id: String,
    timestamp: Long,
    strategyId: String,
): CompiledBotOrder {
    rejectEngineManaged(bot)
    val side = if (bot.action is Buy) Side.BUY else Side.SELL
    val entryPrice = entryPrice(bot, ctx, side)
    val sl =
        bot.opts.bracket
            ?.stopLoss
            ?.let { resolveExit(it, side, entryPrice, null, ctx, isStop = true) }
    val stopDistance = sl?.subtract(entryPrice)?.abs()
    val tp =
        bot.opts.bracket
            ?.takeProfit
            ?.let { resolveExit(it, side, entryPrice, stopDistance, ctx, isStop = false) }
    val lots = resolveBotLots(bot.opts.sizing, ctx, entryPrice, stopDistance)
    val (tif, expiresAt) = resolveTif(bot.opts.tif)
    val entry = entryRequest(bot, id, side, lots, tif, timestamp, strategyId, expiresAt)
    val request =
        if (sl != null && tp != null) {
            OrderRequest.Bracket(
                id = id,
                symbol = bot.qktSymbol,
                side = side,
                quantity = lots,
                entry = entry,
                takeProfit = tp,
                stopLoss = StopLossSpec.Fixed(sl),
                timeInForce = tif,
                timestamp = timestamp,
                strategyId = strategyId,
                expiresAt = expiresAt,
                takeProfitAst = bot.opts.bracket?.takeProfit,
                stopLossAst = bot.opts.bracket?.stopLoss,
            )
        } else {
            entry
        }
    return CompiledBotOrder(request = request, stopLoss = sl, takeProfit = tp)
}

private fun rejectEngineManaged(bot: BotAction) {
    val opts = bot.opts
    val managed =
        when {
            opts.orderType is com.qkt.dsl.ast.TrailingBy || opts.orderType is com.qkt.dsl.ast.TrailingPct ->
                "TRAILING entries"
            opts.bracket?.stopLoss is com.qkt.dsl.ast.ChildArmedTrail -> "armed-trail stops"
            opts.stack != null -> "STACK"
            opts.stackAts.isNotEmpty() -> "STACK_AT"
            opts.oco != null -> "OCO"
            opts.onFill.isNotEmpty() -> "ON FILL"
            else -> null
        }
    require(managed == null) {
        "$managed need a persistent engine to manage and are not supported one-shot; " +
            "deploy a .qkt strategy instead (qkt deploy)"
    }
}

private fun entryPrice(
    bot: BotAction,
    ctx: BotQuoteContext,
    side: Side,
): BigDecimal =
    when (val ot = bot.opts.orderType) {
        null, is Market -> if (side == Side.BUY) ctx.ask else ctx.bid
        is Limit -> literal(ot.price, "LIMIT AT")
        is Stop -> literal(ot.price, "STOP AT")
        is StopLimit -> literal(ot.limitPrice, "STOP LIMIT AT")
        else -> error("unsupported order type ${ot::class.simpleName}")
    }

private fun resolveExit(
    child: ChildPriceAst,
    side: Side,
    entry: BigDecimal,
    stopDistance: BigDecimal?,
    ctx: BotQuoteContext,
    isStop: Boolean,
): BigDecimal {
    val price =
        when (child) {
            is ChildAt -> literal(child.price, "exit AT")
            is ChildBy -> applyDistance(side, entry, literal(child.distance, "exit BY"), isStop)
            is ChildPct -> {
                val pct = literal(child.percent, "exit PCT")
                val dist = entry.multiply(pct, Money.CONTEXT).divide(BigDecimal(100), Money.CONTEXT)
                applyDistance(side, entry, dist, isStop)
            }
            is ChildRr -> {
                require(!isStop) { "RR is take-profit only" }
                val sd =
                    stopDistance
                        ?: error("RR take-profit requires a stop loss to derive the distance")
                applyDistance(side, entry, literal(child.multiplier, "exit RR").multiply(sd, Money.CONTEXT), isStop)
            }
            else -> error("exit form ${child::class.simpleName} is not supported one-shot")
        }
    require(price.signum() > 0) { "resolved exit price must be > 0: $price" }
    return ctx.digits?.let { price.setScale(it, RoundingMode.HALF_UP) } ?: price
}

private fun applyDistance(
    side: Side,
    entry: BigDecimal,
    dist: BigDecimal,
    isStop: Boolean,
): BigDecimal {
    val stopSide = if (side == Side.BUY) -1 else 1
    val sign = BigDecimal(if (isStop) stopSide else -stopSide)
    return entry.add(sign.multiply(dist, Money.CONTEXT), Money.CONTEXT)
}

private fun resolveTif(tif: TifAst?): Pair<TimeInForce, Long?> =
    when (tif) {
        null, is Gtc -> TimeInForce.GTC to null
        is Day -> TimeInForce.DAY to null
        is Gtd -> TimeInForce.GTD to literal(tif.until, "TIF GTD").toLong()
        else -> error("TIF ${tif::class.simpleName} is not supported one-shot; use GTC, DAY, or GTD")
    }

private fun entryRequest(
    bot: BotAction,
    id: String,
    side: Side,
    lots: BigDecimal,
    tif: TimeInForce,
    timestamp: Long,
    strategyId: String,
    expiresAt: Long?,
): OrderRequest =
    when (val ot = bot.opts.orderType) {
        null, is Market ->
            OrderRequest.Market(
                id = id,
                symbol = bot.qktSymbol,
                side = side,
                quantity = lots,
                timeInForce = tif,
                timestamp = timestamp,
                strategyId = strategyId,
            )
        is Limit ->
            OrderRequest.Limit(
                id = id,
                symbol = bot.qktSymbol,
                side = side,
                quantity = lots,
                limitPrice = literal(ot.price, "LIMIT AT"),
                timeInForce = tif,
                timestamp = timestamp,
                strategyId = strategyId,
                expiresAt = expiresAt,
            )
        is Stop ->
            OrderRequest.Stop(
                id = id,
                symbol = bot.qktSymbol,
                side = side,
                quantity = lots,
                stopPrice = literal(ot.price, "STOP AT"),
                timeInForce = tif,
                timestamp = timestamp,
                strategyId = strategyId,
                expiresAt = expiresAt,
            )
        is StopLimit ->
            OrderRequest.StopLimit(
                id = id,
                symbol = bot.qktSymbol,
                side = side,
                quantity = lots,
                stopPrice = literal(ot.stopPrice, "STOP AT"),
                limitPrice = literal(ot.limitPrice, "LIMIT AT"),
                timeInForce = tif,
                timestamp = timestamp,
                strategyId = strategyId,
                expiresAt = expiresAt,
            )
        else -> error("unsupported order type ${ot::class.simpleName}")
    }
