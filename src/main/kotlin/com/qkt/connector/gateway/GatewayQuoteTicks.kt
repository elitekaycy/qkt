package com.qkt.connector.gateway

import com.qkt.derivatives.options.chain.ChainQuote
import com.qkt.derivatives.options.chain.QuoteSides
import com.qkt.instrument.QuoteSource
import com.qkt.marketdata.Tick
import java.math.BigDecimal

/**
 * [quote] as a tick of [qktSymbol], or null when it carries no price. The price is the venue's mark
 * (the convention option backtests price at) or, without one, the mid of a two-sided book; a mark at
 * or below zero is no price. The tick's bid and ask are the book's tradeable sides ([QuoteSides.book]),
 * each with its size.
 */
internal fun gatewayQuoteTick(
    qktSymbol: String,
    quote: WireQuote,
): Tick? {
    val sides = QuoteSides.book(quote.bid?.let(::BigDecimal), quote.ask?.let(::BigDecimal))
    val book =
        Tick(
            qktSymbol,
            BigDecimal.ZERO,
            quote.time,
            bid = sides.bid,
            ask = sides.ask,
            bidVolume = quote.bidSize?.takeIf { sides.bid != null }?.let(::BigDecimal),
            askVolume = quote.askSize?.takeIf { sides.ask != null }?.let(::BigDecimal),
        )
    val mark = quote.mark?.let(::BigDecimal)
    val price = if (mark != null) mark.takeIf { it.signum() > 0 } else book.mid
    return price?.let { book.copy(price = it) }
}

/**
 * [quote] as a book chain quote of its venue code, as chain history keeps it: its own bid, ask, mark,
 * mark IV (volatility points), underlying (the expiry's forward) and fee index, fresh at its own time. Null when it
 * has no positive mark or underlying, which no chain quote can be without.
 */
internal fun gatewayChainQuote(quote: WireQuote): ChainQuote? {
    val mark = quote.mark?.let(::BigDecimal)?.takeIf { it.signum() > 0 } ?: return null
    val underlying = quote.underlying?.let(::BigDecimal)?.takeIf { it.signum() > 0 } ?: return null
    return ChainQuote(
        atMs = quote.time,
        contract = quote.symbol,
        bid = quote.bid?.let(::BigDecimal),
        ask = quote.ask?.let(::BigDecimal),
        mark = mark,
        markIv = quote.markIv?.let(::BigDecimal),
        underlying = underlying,
        rate = null,
        markAgeMs = 0L,
        source = QuoteSource.BOOK,
        index = quote.index?.let(::BigDecimal)?.takeIf { it.signum() > 0 },
    )
}
