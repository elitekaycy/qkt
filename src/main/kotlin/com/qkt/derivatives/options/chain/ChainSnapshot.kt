package com.qkt.derivatives.options.chain

import com.qkt.instrument.QuoteSource
import java.math.BigDecimal

/**
 * One option contract of a chain at [atMs]: its book ([bid], [ask], either may be absent), its [mark]
 * and mark implied volatility ([markIv], in percent), the [underlying] price it was valued against
 * (the index at a trade, the expiry's forward in a book), the venue's [rate] when given, and
 * [markAgeMs], how old the mark was at [atMs] (time since the book row or the trade it came from).
 * [contract] is the code without venue, e.g. `BTC_USDC-27SEP24-60000-C`.
 */
data class ChainQuote(
    val atMs: Long,
    val contract: String,
    val bid: BigDecimal?,
    val ask: BigDecimal?,
    val mark: BigDecimal,
    val markIv: BigDecimal?,
    val underlying: BigDecimal,
    val rate: BigDecimal?,
    val markAgeMs: Long,
    val source: QuoteSource,
) {
    init {
        require(
            contract.isNotBlank() && ',' !in contract,
        ) { "ChainQuote.contract must be a code without commas: '$contract'" }
        require(markAgeMs >= 0) { "ChainQuote.markAgeMs must be >= 0: $markAgeMs" }
        require(underlying.signum() > 0) { "ChainQuote.underlying must be > 0: $underlying" }
    }
}

/**
 * Every quote of [root]'s chain at one instant [atMs]: at least one, one per contract. An instant
 * with nothing quoted has no snapshot (the store could not tell it from one never taken).
 */
data class ChainSnapshot(
    val root: String,
    val atMs: Long,
    val quotes: List<ChainQuote>,
) {
    init {
        require(quotes.all { it.atMs == atMs }) { "ChainSnapshot $root at $atMs holds quotes of another instant" }
        require(quotes.isNotEmpty()) { "ChainSnapshot $root at $atMs has no quotes" }
        require(
            quotes.distinctBy { it.contract }.size == quotes.size,
        ) { "ChainSnapshot $root at $atMs repeats a contract" }
    }
}
