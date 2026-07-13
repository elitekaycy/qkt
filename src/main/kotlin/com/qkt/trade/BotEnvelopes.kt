package com.qkt.trade

import com.qkt.cli.BuildInfo
import com.qkt.execution.OrderRequest
import com.qkt.observe.insights.InsightsEnvelope

/**
 * The one-shot analogue of the `strategy.started` metadata envelope: identifies a bot
 * command to qkt-insights by its canonical DSL source, source sha256, qkt version, and
 * the raw argv it came from. `strategyId` is the `--as` attribution name.
 */
fun botCommandEnvelope(
    asName: String,
    action: BotAction,
    argv: List<String>,
    ts: Long,
    seq: Long,
): InsightsEnvelope =
    InsightsEnvelope(
        id = "bot-command-${action.sha256.take(12)}-$ts",
        seq = seq,
        ts = ts,
        strategyId = asName,
        type = "bot.command",
        payload =
            mapOf(
                "source" to action.source,
                "sha256" to action.sha256,
                "qktVersion" to BuildInfo.VERSION,
                "argv" to argv,
                "symbol" to action.qktSymbol,
            ),
    )

/** Mirrors the live pipeline's `order.submit` payload for a one-shot bot order. */
fun botOrderSubmitEnvelope(
    asName: String,
    order: CompiledBotOrder,
    ts: Long,
    seq: Long,
): InsightsEnvelope {
    val request = order.request
    return InsightsEnvelope(
        id = "bot-submit-${request.id}-$ts",
        seq = seq,
        ts = ts,
        strategyId = asName,
        type = "order.submit",
        payload =
            mapOf(
                "orderId" to request.id,
                "orderType" to request.javaClass.simpleName,
                "symbol" to request.symbol,
                "side" to request.side.name,
                "qty" to request.quantity,
                "strategyId" to asName,
                "timeInForce" to request.timeInForce.name,
                "createdTs" to request.timestamp,
                "expiresAt" to request.expiresAt,
                "stopLoss" to order.stopLoss,
                "takeProfit" to order.takeProfit,
                "limitPrice" to (request as? OrderRequest.Limit)?.limitPrice,
                "stopPrice" to (request as? OrderRequest.Stop)?.stopPrice,
            ),
    )
}

/** The venue's ack or rejection of a one-shot bot order, as `order.accepted`/`order.rejected`. */
fun botOrderResultEnvelope(
    asName: String,
    order: CompiledBotOrder,
    result: BotPlaceResult,
    ts: Long,
    seq: Long,
): InsightsEnvelope =
    InsightsEnvelope(
        id = "bot-result-${order.request.id}-$ts",
        seq = seq,
        ts = ts,
        strategyId = asName,
        type = if (result.ok) "order.accepted" else "order.rejected",
        payload =
            mapOf(
                "orderId" to order.request.id,
                "brokerOrderId" to result.ticket.toString(),
                "deal" to result.deal,
                "price" to result.price,
                "retcode" to result.retcode,
                "reason" to result.error,
            ),
    )
