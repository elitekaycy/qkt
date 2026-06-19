package com.qkt.risk.rules

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import com.qkt.risk.book.BookRiskController
import com.qkt.risk.isRiskReducing
import java.math.BigDecimal

/**
 * Book-level exposure gate. Rejects a risk-INCREASING order that would push the book's gross, net, or
 * per-symbol exposure past the configured caps; risk-reducing orders always pass (never block the way
 * out). Reads the latest book state from [controller] and values the order at last price x contract
 * size. When the order's symbol has no last price, it approves rather than block on missing data.
 */
class BookExposureLimit(
    private val controller: BookRiskController,
    private val prices: MarketPriceProvider,
    private val instruments: InstrumentRegistry,
) : RiskRule {
    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        if (isRiskReducing(request, positions)) return Decision.Approve
        val price = prices.lastPrice(request.symbol) ?: return Decision.Approve
        val cs = instruments.lookup(request.symbol)?.contractSize ?: BigDecimal.ONE
        val notional = request.quantity.multiply(price, Money.CONTEXT).multiply(cs, Money.CONTEXT)
        val signed = if (request.side == Side.BUY) notional else notional.negate()
        val breach = controller.state().limitBreach(request.symbol, signed) ?: return Decision.Approve
        return Decision.Reject(breach)
    }
}
