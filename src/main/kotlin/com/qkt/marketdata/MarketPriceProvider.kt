package com.qkt.marketdata

interface MarketPriceProvider {
    fun lastPrice(symbol: String): Double?
}

class MarketPriceTracker : MarketPriceProvider {
    private val prices = mutableMapOf<String, Double>()

    fun update(
        symbol: String,
        price: Double,
    ) {
        prices[symbol] = price
    }

    override fun lastPrice(symbol: String): Double? = prices[symbol]
}
