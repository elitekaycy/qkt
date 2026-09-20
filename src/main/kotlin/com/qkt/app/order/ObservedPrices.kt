package com.qkt.app.order

import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal

/**
 * The last tick price the order manager saw per symbol, falling back to the market price
 * provider before the first tick. Seeds trailing stops and estimates a market entry's price.
 * Written once per tick on the engine thread.
 */
internal class ObservedPrices(
    private val priceProvider: MarketPriceProvider,
) {
    private val lastBySymbol: MutableMap<String, BigDecimal> = mutableMapOf()

    fun record(
        symbol: String,
        price: BigDecimal,
    ) {
        lastBySymbol[symbol] = price
    }

    /** Last seen price for [symbol], else the provider's, else null when neither has quoted it. */
    fun priceOf(symbol: String): BigDecimal? = lastBySymbol[symbol] ?: priceProvider.lastPrice(symbol)
}
