package com.qkt.marketdata

import com.qkt.common.Side
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * The price at which exposure of [signum] (positive long, negative short) could be closed
     * right now: a long sells at the bid, a short buys at the ask. Flat exposure and providers
     * without quote depth mark at [lastPrice]. Unrealized P&L marks here rather than at mid, so
     * the half-spread the venue charges on the way out is never counted as open profit.
     */
    fun closingMark(
        symbol: String,
        signum: Int,
    ): BigDecimal? =
        when {
            signum > 0 -> executionPrice(symbol, Side.SELL)
            signum < 0 -> executionPrice(symbol, Side.BUY)
            else -> lastPrice(symbol)
        }
}

/**
 * Mutable price store updated by the engine and safely read by broker worker threads.
 *
 * Implements [MarketPriceProvider]; expose to consumers via the interface type so they
 * can't accidentally write. Each update replaces one immutable snapshot atomically.
 */
class MarketPriceTracker : MarketPriceProvider {
    private data class PriceSnapshot(
        val last: BigDecimal,
        val buyExecution: BigDecimal,
        val sellExecution: BigDecimal,
    )

    private val prices = ConcurrentHashMap<String, PriceSnapshot>()

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
        prices[symbol] = PriceSnapshot(last, buyExecution, sellExecution)
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
