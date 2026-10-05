package com.qkt.dsl.compile

import com.qkt.dsl.ast.StrategyAst
import com.qkt.marketdata.depth.BookDepthSymbol

/**
 * Rewrites `<alias>.bid_depth`, `.ask_depth` and `.book_imbalance` into hidden streams of their own, as
 * [OpenInterestFieldExpansion] does open interest:
 *
 *     perp = DERIBIT:BTC_USDC_PERPETUAL EVERY 1m      WHEN perp.book_imbalance > 0.3 ...
 *
 * becomes, beside `perp`,
 *
 *     perp/book_imbalance = DEPTH:IMBALANCE:DERIBIT:BTC_USDC_PERPETUAL EVERY 1m      WHEN perp/book_imbalance.close > 0.3 ...
 *
 * Each hidden stream carries one observation per book snapshot, at the instant the venue stamped it
 * ([BookDepthSymbol]), so indicators, lookback and warmup work on depth as on a price.
 */
object BookDepthFieldExpansion {
    fun apply(ast: StrategyAst): StrategyAst =
        VenueFieldExpansion.apply(ast) { field, venue ->
            if (field in
                BookDepthSymbol.FIELDS
            ) {
                BookDepthSymbol.BROKER to BookDepthSymbol.symbolOf(field, venue.qktSymbol)
            } else {
                null
            }
        }
}
