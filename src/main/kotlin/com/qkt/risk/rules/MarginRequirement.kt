package com.qkt.risk.rules

import com.qkt.accounting.margin.MarginModel
import com.qkt.accounting.margin.OptionMargin
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import com.qkt.risk.isRiskReducing
import java.math.BigDecimal

/**
 * Refuses an order on a margined instrument when the account's [equity] could not carry the initial
 * margin of every margined position after it. Each symbol's exposure is the larger of what it would
 * hold if all its pending buys or all its pending sells filled (this order included), so a burst of
 * entries cannot outrun the check. With [options], the account's option positions add their
 * worst-case requirement ([OptionMargin]) and an option order is judged too; one that would leave an
 * unbounded loss is refused. Orders that only reduce a position always pass; symbols without margin
 * terms that are not options are not judged.
 */
class MarginRequirement(
    private val margin: MarginModel,
    private val prices: MarketPriceProvider,
    private val options: OptionMargin? = null,
    private val equity: () -> BigDecimal,
) : RiskRule {
    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        val isOption = options?.covers(request.symbol) == true
        if (!(margin.hasTerms(request.symbol) || isOption) ||
            isRiskReducing(request, positions)
        ) {
            return Decision.Approve
        }
        val symbols =
            (
                positions.symbols() +
                    positions.pendingEntrySymbols(
                        null,
                    ) + request.symbol
            ).filter(margin::hasTerms)
        var required = BigDecimal.ZERO
        for (symbol in symbols) {
            val price =
                (if (symbol == request.symbol) explicitPrice(request) else null)
                    ?: prices.lastPrice(symbol)
                    ?: positions.positionFor(symbol)?.avgEntryPrice
                    ?: return Decision.Reject("cannot compute margin for $symbol: no price reference")
            required =
                required.add(margin.initial(symbol, exposure(symbol, request, positions), price, request.timestamp))
        }
        if (options != null) {
            val mark = { s: String ->
                (
                    if (s ==
                        request.symbol
                    ) {
                        explicitPrice(request)
                    } else {
                        null
                    }
                ) ?: prices.lastPrice(s)
            }
            when (val outcome = options.required(request.symbol, request.side, request.quantity, positions, mark)) {
                is OptionMargin.Outcome.Refused -> return Decision.Reject(outcome.reason)
                is OptionMargin.Outcome.Required -> required = required.add(outcome.amount)
            }
        }
        val available = equity()
        if (required <= available) return Decision.Approve
        return Decision.Reject(
            "initial margin ${required.toPlainString()} after this order exceeds account equity ${available.toPlainString()}",
        )
    }

    private fun exposure(
        symbol: String,
        request: OrderRequest,
        positions: PositionProvider,
    ): BigDecimal {
        val net = positions.positionFor(symbol)?.quantity ?: BigDecimal.ZERO
        var buys = positions.pendingOrderQuantity(symbol, Side.BUY)
        var sells = positions.pendingOrderQuantity(symbol, Side.SELL)
        if (symbol == request.symbol) {
            if (request.side == Side.BUY) buys = buys.add(request.quantity) else sells = sells.add(request.quantity)
        }
        return net.add(buys).abs().max(net.subtract(sells).abs())
    }

    private fun explicitPrice(request: OrderRequest): BigDecimal? =
        when (request) {
            is OrderRequest.Limit -> request.limitPrice
            is OrderRequest.Stop -> request.stopPrice
            is OrderRequest.StopLimit -> request.stopPrice
            is OrderRequest.IfTouched -> request.triggerPrice
            is OrderRequest.Bracket -> explicitPrice(request.entry)
            else -> null
        }
}
