package com.qkt.accounting

import com.qkt.instrument.QuoteCurrencyGuard

/** The base and quote a symbol names: `ETH`/`USDT` of `BYBIT_SPOT:ETHUSDT`, `BTC`/`USDC` of `DERIBIT:BTC_USDC`. */
internal data class CurrencyPair(
    val base: String,
    val quote: String,
) {
    companion object {
        /**
         * [symbol]'s pair, read from its known quote tail with any `_`, `-` or `/` between base and quote dropped;
         * null when no known quote ends it or nothing precedes the quote.
         */
        fun of(symbol: String): CurrencyPair? {
            val bare = symbol.substringAfter(':').uppercase()
            val quote = QuoteCurrencyGuard.quoteOf(bare) ?: return null
            val base = bare.removeSuffix(quote).trimEnd('_', '-', '/')
            if (base.isBlank()) return null
            return CurrencyPair(base = base, quote = quote)
        }
    }
}
