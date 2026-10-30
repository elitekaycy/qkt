package com.qkt.marketdata

import java.util.concurrent.atomic.AtomicLong
import org.slf4j.Logger

/**
 * Drops a side of a book too far from its tick's price to be a market. A venue's mark passes [MarketDataGate] while a
 * stray far quote on a thin book sits in the side a short is marked at: e.g. at a price of 79,000 an ask of 240,505
 * goes, an ask of 81,000 stays. A side that went falls back to the price wherever it is read ([buyExecPrice],
 * [sellExecPrice]), as a one-sided book's does. Not for a symbol whose prices are not [judged]: an option's book is
 * wide by nature. A judgement of data quality on Double, not money math. O(1), allocating only when a side goes.
 */
internal class AbsurdQuoteSides(
    private val judged: (symbol: String) -> Boolean,
    private val log: Logger,
) {
    private val dropped = AtomicLong(0)

    /** [tick] without its absurd sides, or [tick] itself when it has none. */
    fun without(tick: Tick): Tick {
        val price = tick.price.toDouble()
        if (price <= 0.0 || !judged(tick.symbol)) return tick
        val limit = price * MAX_DISTANCE
        val keepBid = tick.bid == null || kotlin.math.abs(tick.bid.toDouble() - price) <= limit
        val keepAsk = tick.ask == null || kotlin.math.abs(tick.ask.toDouble() - price) <= limit
        if (keepBid && keepAsk) return tick
        val n = dropped.incrementAndGet()
        if (n == 1L || n % LOG_EVERY == 0L) {
            log.warn(
                "dropping an absurd book side #{} for {}: price={} bid={} ask={}",
                n,
                tick.symbol,
                tick.price.toPlainString(),
                tick.bid?.toPlainString(),
                tick.ask?.toPlainString(),
            )
        }
        return tick.copy(
            bid = tick.bid.takeIf { keepBid },
            bidVolume = tick.bidVolume.takeIf { keepBid },
            ask = tick.ask.takeIf { keepAsk },
            askVolume = tick.askVolume.takeIf { keepAsk },
        )
    }

    private companion object {
        /** A fraction of the price, far wider than any real spread: a normal book still marks at its own sides. */
        const val MAX_DISTANCE = 0.10
        const val LOG_EVERY = 500L
    }
}
