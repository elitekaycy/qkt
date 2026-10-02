package com.qkt.marketdata.source

import com.qkt.derivatives.options.chain.ChainQuote
import com.qkt.derivatives.options.chain.OptionQuotes
import com.qkt.instrument.OptionRoot
import com.qkt.marketdata.Tick

/** [quote] of option [symbol] as a tick: priced at its mark, with the bid and ask [OptionQuotes] allows. */
internal fun optionQuoteTick(
    symbol: String,
    quote: ChainQuote,
    root: OptionRoot,
): Tick {
    val sides = OptionQuotes.sides(quote, root)
    return Tick(symbol, quote.mark, quote.atMs, bid = sides.bid, ask = sides.ask)
}
