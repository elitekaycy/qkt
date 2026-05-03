package com.qkt.marketdata

import java.math.BigDecimal

interface MarketPriceProvider {
    fun lastPrice(symbol: String): BigDecimal?
}

class MarketPriceTracker : MarketPriceProvider {
    private val prices = mutableMapOf<String, BigDecimal>()

    fun update(
        symbol: String,
        price: BigDecimal,
    ) {
        prices[symbol] = price
    }

    override fun lastPrice(symbol: String): BigDecimal? = prices[symbol]
}
