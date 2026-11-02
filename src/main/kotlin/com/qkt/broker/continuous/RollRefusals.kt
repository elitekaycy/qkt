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
    val priceBand: BigDecimal?,
) {
    /** Why strategy [id]'s signed [quantity] may never be carried out of [from], or null: this does not pass with time. */
    fun of(
        id: String,
        quantity: BigDecimal,
        from: String,
        measured: MeasuredRoll,
    ): String? {
        val onContract = book.positionFor(id, from)?.quantity ?: BigDecimal.ZERO
        if (onContract.compareTo(quantity) != 0) {
            return "holds ${onContract.toPlainString()} of $from, not its ${quantity.toPlainString()} on the stream"
        }
        priceBand ?: return null
        val prices = measured.prices
        if (prices.fromPrice.signum() <= 0 || prices.toPrice.signum() <= 0) {
            return "no collar around the measured ${prices.fromPrice}->${prices.toPrice}: not positive"
        }
        return null
    }

    /** Why the quotes bar signed [quantity]'s legs now, or null; a far or missing quote may pass ([RollQuoteWait]). */
    fun ofQuotes(
        quantity: BigDecimal,
        measured: MeasuredRoll,
        quotes: RollQuotes,
    ): String? {
        val band = priceBand ?: return null
        val closing = if (quantity.signum() > 0) Side.SELL else Side.BUY
        val opening = if (quantity.signum() > 0) Side.BUY else Side.SELL
        return quoteRefusal(quotes.from, closing, measured.prices.fromPrice, band)
            ?: quoteRefusal(quotes.to, opening, measured.prices.toPrice, band)
    }
}

/**
 * Why a leg must not go: the side of [quote] it would hit (ask for a BUY, bid for a SELL) is missing, or lies
 * further than [band] (a fraction) from [reference], the contract's measured roll price. E.g. at a band of 0.25 and a
 * reference of 78,000, an ask of 240,505 refuses a BUY; a bid of 77,000 passes a SELL. A feed with no book sides is
 * judged by its price, and with no quote heard there is nothing to judge. A tick whose two sides `AbsurdQuoteSides`
 * dropped looks the same and passes on its price: this is an early look, and what bounds the fill is the leg's own
 * collar ([collarPrice]), which holds however far the book is when the leg arrives.
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
