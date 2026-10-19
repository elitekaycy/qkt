package com.qkt.trade

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import com.qkt.risk.rules.MaxOrderQty
import com.qkt.risk.rules.PreTradeControls
import com.qkt.risk.rules.PriceCollar
import java.math.BigDecimal

/**
 * The config's pre-trade controls on a one-shot order, before it reaches the venue: the engine's own
 * [MaxOrderQty] and [PriceCollar] rules against the quote, and the notional cap as quantity x price x the
 * venue's contract size in the quote currency (a one-shot command has no FX rates, so a cap in another
 * currency is not converted). Throws naming the config key; returns when every control passes.
 */
fun checkBotPreTrade(
    order: CompiledBotOrder,
    ctx: BotQuoteContext,
    maxOrderQty: BigDecimal,
    maxOrderNotional: BigDecimal,
    priceCollarFrac: BigDecimal,
) {
    val request = order.request
    // The engine's collar measures against the venue's price (a mark where the venue has one), not a
    // thin book's mid.
    val mid = ctx.reference ?: ctx.bid.add(ctx.ask).divide(BigDecimal(2))
    val prices =
        object : MarketPriceProvider {
            override fun lastPrice(symbol: String): BigDecimal = mid
        }
    reject("risk.max_order_qty", MaxOrderQty(maxOrderQty), request)
    reject("risk.price_collar_pct", PriceCollar(priceCollarFrac, prices), request)
    val reference = explicitPrice(request) ?: if (request.side == Side.BUY) ctx.ask else ctx.bid
    val size = ctx.contractSize
    if (size == null) {
        require(maxOrderNotional >= PreTradeControls.DEFAULT_MAX_ORDER_NOTIONAL) {
            "risk.max_order_notional: notional cannot be checked: the venue reported no contract size for ${request.symbol}"
        }
        return
    }
    val notional = request.quantity.multiply(reference).multiply(size)
    require(notional <= maxOrderNotional) {
        val amount = "${notional.stripTrailingZeros().toPlainString()} ${ctx.quoteCurrency.orEmpty()}".trimEnd()
        "risk.max_order_notional: order notional $amount exceeds ${maxOrderNotional.toPlainString()}"
    }
}

private fun reject(
    key: String,
    rule: RiskRule,
    request: OrderRequest,
) {
    val decision = rule.evaluate(request, NoPositions)
    require(decision !is Decision.Reject) { "$key: ${(decision as Decision.Reject).reason}" }
}

private fun explicitPrice(request: OrderRequest): BigDecimal? =
    when (request) {
        is OrderRequest.Limit -> request.limitPrice
        is OrderRequest.Stop -> request.stopPrice
        is OrderRequest.StopLimit -> request.limitPrice
        is OrderRequest.Bracket -> explicitPrice(request.entry)
        else -> null
    }

private object NoPositions : PositionProvider {
    override fun positionFor(symbol: String): Position? = null

    override fun allPositions(): Map<String, Position> = emptyMap()
}
