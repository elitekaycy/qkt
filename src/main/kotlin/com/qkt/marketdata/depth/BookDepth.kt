package com.qkt.marketdata.depth

import com.qkt.common.Money
import java.math.BigDecimal

/** One price level of a book: [amount] resting at [price], in the contract's order quantity. */
data class BookLevel(
    val price: BigDecimal,
    val amount: BigDecimal,
) {
    init {
        require(price.signum() > 0) { "BookLevel.price must be positive: $price" }
        require(amount.signum() > 0) { "BookLevel.amount must be positive: $amount" }
    }
}

/**
 * One snapshot of a contract's order book, the same shape whatever the venue: at most ten levels a side,
 * [bids] from the highest price down and [asks] from the lowest up, known from [timeMs] on. [timeMs] is when
 * the venue stamped the book, so a strategy reading it at [timeMs] could have read it live.
 */
data class BookDepth(
    val timeMs: Long,
    val bids: List<BookLevel>,
    val asks: List<BookLevel>,
) {
    /** The quantity resting on the bid levels held, the contract's order quantity. */
    val bidDepth: BigDecimal get() = bids.fold(BigDecimal.ZERO) { sum, level -> sum.add(level.amount) }

    /** The quantity resting on the ask levels held. */
    val askDepth: BigDecimal get() = asks.fold(BigDecimal.ZERO) { sum, level -> sum.add(level.amount) }

    /**
     * `(bid_depth − ask_depth) / (bid_depth + ask_depth)`, from −1 (only offers) to 1 (only bids); 0 when the
     * book holds neither side.
     */
    val imbalance: BigDecimal
        get() {
            val bid = bidDepth
            val ask = askDepth
            val total = bid.add(ask)
            return if (total.signum() == 0) BigDecimal.ZERO else bid.subtract(ask).divide(total, Money.CONTEXT)
        }
}

/** Where a contract's order-book snapshots come from: a gateway account live, the stored series in a backtest. */
fun interface BookDepthSource {
    /** The snapshots of [qktSymbol] known from [fromMs] to [toMs], oldest first. */
    fun snapshots(
        qktSymbol: String,
        fromMs: Long,
        toMs: Long,
    ): List<BookDepth>

    /** The same snapshots as a sequence, which a source holding many may read a part at a time. */
    fun sequence(
        qktSymbol: String,
        fromMs: Long,
        toMs: Long,
    ): Sequence<BookDepth> = snapshots(qktSymbol, fromMs, toMs).asSequence()
}
