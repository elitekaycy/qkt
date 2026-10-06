package com.qkt.dsl.compile

import com.qkt.dsl.ast.StrategyAst
import com.qkt.marketdata.openinterest.OpenInterestSymbol

/**
 * Rewrites `<alias>.open_interest` into a hidden stream of its own, the way a hub field is
 * ([HubFieldExpansion]):
 *
 *     perp = DERIBIT:BTC_USDC_PERPETUAL EVERY 1m      WHEN perp.open_interest > 1000 ...
 *
 * becomes, beside `perp`,
 *
 *     perp/open_interest = OI:DERIBIT:BTC_USDC_PERPETUAL EVERY 1m      WHEN perp/open_interest.close > 1000 ...
 *
 * The hidden stream carries one observation per published figure, at the instant the venue made it known
 * ([OpenInterestSymbol]), so indicators, lookback and warmup work on open interest as on a price, and the
 * backtest and live feeds route it like any other stream: from the stored series, or from the account's
 * gateway. The walk is [VenueFieldExpansion]'s.
 */
object OpenInterestFieldExpansion {
    fun apply(ast: StrategyAst): StrategyAst =
        VenueFieldExpansion.apply(ast) { field, venue ->
            (OpenInterestSymbol.BROKER to venue.qktSymbol).takeIf { field == OpenInterestSymbol.FIELD }
        }
}
