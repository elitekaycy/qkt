package com.qkt.risk.rules

import com.qkt.accounting.margin.MarginModel
import com.qkt.accounting.margin.OptionMargin
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.GroupAwareRule
import com.qkt.risk.isRiskReducing
import java.math.BigDecimal

/**
 * Refuses an order on a margined instrument when the account's [equity] could not carry the initial
 * margin of every margined position after it. Each symbol's exposure is the larger of what it would
 * hold if all its pending buys or all its pending sells filled (this order included), so a burst of
 * entries cannot outrun the check.
 *
 * With [options], the account's option positions add their worst-case requirement ([OptionMargin]),
 * and option orders are judged on that portfolio rather than per symbol (a futures order is refused
 * too while the options cannot be margined). An option order that would
 * leave an unbounded loss is refused, even one that reduces its own symbol (selling the long wing of
 * a call spread leaves the short naked). One that lowers the options' requirement passes even when
 * equity no longer covers it. A non-option order that only reduces a position always passes, and
 * symbols without margin terms that are not options are not judged.
 */
class MarginRequirement(
    private val margin: MarginModel,
    private val prices: MarketPriceProvider,
    private val options: OptionMargin? = null,
    private val equity: () -> BigDecimal,
) : GroupAwareRule {
    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        val isOption = options?.covers(request.symbol) == true
        if (!isOption &&
            (!margin.hasTerms(request.symbol) || isRiskReducing(request, positions))
        ) {
            return Decision.Approve
        }
        var unpriced: String? = null
        val futures = futuresRequirement(request, positions) { unpriced = it }
        unpriced?.let { return Decision.Reject("cannot compute margin for $it: no price reference") }
        val optionsAfter = optionRequirement(request, request.quantity, positions)
        if (optionsAfter is OptionMargin.Outcome.Refused) return Decision.Reject(optionsAfter.reason)
        val afterAmount = (optionsAfter as? OptionMargin.Outcome.Required)?.amount ?: BigDecimal.ZERO
        val required = futures.add(afterAmount)
        val available = equity()
        if (required <= available) return Decision.Approve
        if (isOption) {
            val before = optionRequirement(request, BigDecimal.ZERO, positions)
            if (before !is OptionMargin.Outcome.Required || afterAmount < before.amount) return Decision.Approve
        }
        return Decision.Reject(
            "initial margin ${required.toPlainString()} after this order exceeds account equity ${available.toPlainString()}",
        )
    }

    /**
     * The legs of an option structure, judged as one position: the options' worst case with every
     * leg filled, plus the futures already held, against equity. A group that would leave an unbounded
     * loss is refused; one that lowers the options' requirement passes. A group with a leg that is not
     * an option is judged leg by leg.
     */
    override fun evaluateGroup(
        requests: List<OrderRequest>,
        positions: PositionProvider,
    ): Decision {
        val model = options
        if (model == null || requests.any { !model.covers(it.symbol) }) {
            return requests.map { evaluate(it, positions) }.firstOrNull { it is Decision.Reject } ?: Decision.Approve
        }
        val fills =
            requests.groupBy { it.symbol }.mapValues { (_, legs) ->
                legs.fold(BigDecimal.ZERO) { q, r -> q.add(r.signedQuantity()) }
            }
        val mark = { s: String -> prices.lastPrice(s) ?: positions.positionFor(s)?.avgEntryPrice }
        val after = model.requiredWithFills(fills, positions, mark)
        if (after is OptionMargin.Outcome.Refused) return Decision.Reject(after.reason)
        var unpriced: String? = null
        val futures = futuresRequirement(requests.first(), positions) { unpriced = it }
        unpriced?.let { return Decision.Reject("cannot compute margin for $it: no price reference") }
        val afterAmount = (after as OptionMargin.Outcome.Required).amount
        val required = futures.add(afterAmount)
        val available = equity()
        if (required <= available) return Decision.Approve
        val before = model.requiredWithFills(emptyMap(), positions, mark)
        if (before !is OptionMargin.Outcome.Required || afterAmount < before.amount) return Decision.Approve
        return Decision.Reject(
            "initial margin ${required.toPlainString()} after this structure exceeds account equity ${available.toPlainString()}",
        )
    }

    private fun OrderRequest.signedQuantity(): BigDecimal = if (side == Side.BUY) quantity else quantity.negate()

    /** The futures initial margin after [request]; a margined symbol without a price is reported to [unpriced]. */
    private fun futuresRequirement(
        request: OrderRequest,
        positions: PositionProvider,
        unpriced: (String) -> Unit,
    ): BigDecimal {
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
                    ?: return BigDecimal.ZERO.also { unpriced(symbol) }
            required =
                required.add(margin.initial(symbol, exposure(symbol, request, positions), price, request.timestamp))
        }
        return required
    }

    /**
     * The options' requirement with [quantity] of [request] filled (zero: as things stand); null without
     * options. Options are valued at their last price, else the position's entry price, else, for the
     * ordered contract alone, the order's own price.
     */
    private fun optionRequirement(
        request: OrderRequest,
        quantity: BigDecimal,
        positions: PositionProvider,
    ): OptionMargin.Outcome? {
        val model = options ?: return null
        val mark = { s: String -> optionMark(s, request, positions) }
        return model.required(request.symbol, request.side, quantity, positions, mark)
    }

    private fun optionMark(
        symbol: String,
        request: OrderRequest,
        positions: PositionProvider,
    ): BigDecimal? =
        prices.lastPrice(symbol)
            ?: positions.positionFor(symbol)?.avgEntryPrice
            ?: if (symbol == request.symbol) explicitPrice(request) else null

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
