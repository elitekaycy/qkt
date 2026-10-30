package com.qkt.broker.continuous

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.derivatives.futures.MeasuredRoll
import com.qkt.marketdata.Tick
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal

/**
 * Why a roll must not carry a strategy's position, checked before any leg goes: its stream position is not what the
 * lane's [book] holds on the old contract, or, with a [priceBand] (a live lane's), a leg would hit a quote beyond it
 * ([quoteRefusal]). Null [priceBand]: nothing about the book is judged, as in a backtest.
 */
internal class RollRefusals(
    private val book: StrategyPositionTracker,
    private val priceBand: BigDecimal?,
) {
    /** Why strategy [id]'s signed [quantity] may not be carried out of [from], or null when it may. */
    fun of(
        id: String,
        quantity: BigDecimal,
        from: String,
        measured: MeasuredRoll,
        quotes: RollQuotes,
    ): String? {
        if (id.isNotEmpty()) return null
        val onContract = book.positionFor(id, from)?.quantity ?: BigDecimal.ZERO
        if (onContract.compareTo(quantity) != 0) {
            return "holds ${onContract.toPlainString()} of $from, not its ${quantity.toPlainString()} on the stream"
        }
        val band = priceBand ?: return null
        val closing = if (quantity.signum() > 0) Side.SELL else Side.BUY
        val opening = if (quantity.signum() > 0) Side.BUY else Side.SELL
        return quoteRefusal(quotes.from, closing, measured.prices.fromPrice, band)
            ?: quoteRefusal(quotes.to, opening, measured.prices.toPrice, band)
    }
}

/**
 * Why a market leg must not go: the side of [quote] it would hit (ask for a BUY, bid for a SELL) is missing, or lies
 * further than [band] (a fraction) from [reference], the contract's measured roll price. E.g. at a band of 0.25 and a
 * reference of 78,000, an ask of 240,505 refuses a BUY; a bid of 77,000 passes a SELL. A feed with no book sides is
 * judged by its price, and with no quote heard there is nothing to judge.
 */
internal fun quoteRefusal(
    quote: Tick?,
    side: Side,
    reference: BigDecimal,
    band: BigDecimal,
): String? {
    quote ?: return null
    val name = if (side == Side.BUY) "ask" else "bid"
    val hit =
        when {
            quote.bid == null && quote.ask == null -> quote.price
            side == Side.BUY -> quote.ask
            else -> quote.bid
        }
    hit ?: return "no $name on ${quote.symbol}"
    if (reference.signum() <= 0) return null
    val deviation = hit.subtract(reference).abs().divide(reference, Money.CONTEXT)
    if (deviation <= band) return null
    return "$name ${hit.stripTrailingZeros().toPlainString()} on ${quote.symbol} is beyond " +
        "${band.toPlainString()} of its measured ${reference.toPlainString()}"
}
