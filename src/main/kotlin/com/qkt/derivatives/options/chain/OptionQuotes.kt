package com.qkt.derivatives.options.chain

import com.qkt.instrument.OptionRoot
import com.qkt.instrument.QuoteSource
import java.math.BigDecimal

/** The prices a chain quote can be traded at: [bid] to sell, [ask] to buy; an absent side cannot trade. */
data class QuoteSides(
    val bid: BigDecimal?,
    val ask: BigDecimal?,
) {
    companion object {
        /** A quote nothing can trade on. */
        val NONE = QuoteSides(null, null)

        /** A book's tradeable sides: a missing or non-positive side is absent; a crossed book trades on neither. */
        fun book(
            bid: BigDecimal?,
            ask: BigDecimal?,
        ): QuoteSides {
            val buyable = ask?.takeIf { it.signum() > 0 }
            val sellable = bid?.takeIf { it.signum() > 0 }
            val crossed = sellable != null && buyable != null && sellable > buyable
            return if (crossed) NONE else QuoteSides(sellable, buyable)
        }
    }
}

/**
 * The one rule for which prices of a chain quote are tradeable, shared by the market data a strategy
 * sees and the venue that fills it. A book quote trades on its own bid and ask (a missing or zero side
 * is absent; a crossed book trades on neither). A trade quote has no book, so a mark at most the
 * root's `maxQuoteAgeMinutes` old gets a declared spread: `mark ∓ max(tick at the mark, markSpread ×
 * mark)`, snapped outward on the root's tick grid, with a bid at or below zero absent; an older mark
 * trades on neither side.
 */
object OptionQuotes {
    private const val MS_PER_MINUTE = 60_000L

    /** [quote]'s tradeable sides under [root]'s quote terms. */
    fun sides(
        quote: ChainQuote,
        root: OptionRoot,
    ): QuoteSides =
        when (quote.source) {
            QuoteSource.BOOK -> QuoteSides.book(quote.bid, quote.ask)
            QuoteSource.TRADE -> fromMark(quote, root)
        }

    private fun fromMark(
        quote: ChainQuote,
        root: OptionRoot,
    ): QuoteSides {
        if (quote.markAgeMs > root.maxQuoteAgeMinutes * MS_PER_MINUTE) return QuoteSides.NONE
        val spread = requireNotNull(root.markSpread) { "${root.root} trades a trade-built chain without markSpread" }
        val mark = quote.mark
        val half = root.tickSteps.tickAt(mark).max(spread.multiply(mark))
        val bid = root.tickSteps.floor(mark.subtract(half)).takeIf { it.signum() > 0 }
        return QuoteSides(bid, root.tickSteps.ceil(mark.add(half)))
    }
}
