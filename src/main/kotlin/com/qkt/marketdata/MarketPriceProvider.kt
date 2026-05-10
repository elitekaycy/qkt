package com.qkt.marketdata

import java.math.BigDecimal

/**
 * Read-only view of the latest known price per symbol.
 *
 * Consumers (brokers, indicators, P&L calculation) depend on this read-only interface
 * to enforce the producer/consumer split — only [MarketPriceTracker]-style producers
 * can write.
 */
interface MarketPriceProvider {
    /** Returns the last seen price for [symbol], or `null` if no tick has been ingested. */
    fun lastPrice(symbol: String): BigDecimal?
}

/**
 * Mutable price store updated by exactly one producer (the engine).
 *
 * Implements [MarketPriceProvider]; expose to consumers via the interface type so they
 * can't accidentally write. Not thread-safe; qkt's engine is single-threaded by design.
 */
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
