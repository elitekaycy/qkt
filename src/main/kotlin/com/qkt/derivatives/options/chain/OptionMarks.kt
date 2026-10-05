package com.qkt.derivatives.options.chain

/**
 * Where a strategy reads an option contract's mark implied volatility and forward (`<alias>.iv`, and the
 * Greeks qkt prices from them): live, the newest quote its venue sent; in a backtest, its quote in its root's
 * newest stored chain snapshot at or before the instant asked, so a value is seen only once it was known.
 */
interface OptionMarks {
    /** [qktSymbol]'s newest quote known at [atMs], or null when none is known yet. */
    fun at(
        qktSymbol: String,
        atMs: Long,
    ): ChainQuote?

    /** Why [qktSymbol]'s option marks cannot be read, naming what would serve them; null when they can. */
    fun problem(qktSymbol: String): String? = null
}
