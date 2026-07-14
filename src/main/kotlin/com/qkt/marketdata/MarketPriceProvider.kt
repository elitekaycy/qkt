package com.qkt.marketdata

import com.qkt.common.Side
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

    /**
     * Returns the latest executable price for [side]: ask for BUY and bid for SELL.
     * Providers without quote depth fall back to [lastPrice].
     */
    fun executionPrice(
        symbol: String,
        side: Side,
    ): BigDecimal? = lastPrice(symbol)
}

/**
 * Mutable price store updated by exactly one producer (the engine).
 *
 * Implements [MarketPriceProvider]; expose to consumers via the interface type so they
 * can't accidentally write. Not thread-safe; qkt's engine is single-threaded by design.
 */
class MarketPriceTracker : MarketPriceProvider {
    private class PriceSnapshot(
        var last: BigDecimal,
        var buyExecution: BigDecimal,
        var sellExecution: BigDecimal,
    )

    private val prices = mutableMapOf<String, PriceSnapshot>()

    /** Updates a single-price mark, using it as the execution fallback for both sides. */
    fun update(
        symbol: String,
        price: BigDecimal,
    ) {
        update(symbol, last = price, buyExecution = price, sellExecution = price)
    }

    /** Updates the mark and retains the tick's ask/bid as side-aware execution prices. */
    fun update(tick: Tick) {
        update(
            symbol = tick.symbol,
            last = tick.price,
            buyExecution = tick.buyExecPrice(),
            sellExecution = tick.sellExecPrice(),
        )
    }

    private fun update(
        symbol: String,
        last: BigDecimal,
        buyExecution: BigDecimal,
        sellExecution: BigDecimal,
    ) {
        val current = prices[symbol]
        if (current == null) {
            prices[symbol] = PriceSnapshot(last, buyExecution, sellExecution)
        } else {
            current.last = last
            current.buyExecution = buyExecution
            current.sellExecution = sellExecution
        }
    }

    override fun lastPrice(symbol: String): BigDecimal? = prices[symbol]?.last

    override fun executionPrice(
        symbol: String,
        side: Side,
    ): BigDecimal? =
        when (side) {
            Side.BUY -> prices[symbol]?.buyExecution
            Side.SELL -> prices[symbol]?.sellExecution
        }
}
