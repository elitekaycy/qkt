package com.qkt.derivatives.options.chain

import com.qkt.instrument.OptionSymbols

/** A whole option root as one feed, `OPTIONS:<VENUE>.<ROOT>` (`OPTIONS:DERIBIT.BTC_USDC`) for root [root]. */
data class OptionRootSymbol(
    val root: String,
) {
    /** Whether [qktSymbol] is an option contract of this root (`DERIBIT:BTC_USDC_25DEC26_92000_C`). */
    fun covers(qktSymbol: String): Boolean {
        val venue = root.substringBefore(':')
        return qktSymbol.startsWith("$venue:") &&
            OptionSymbols.venueName(qktSymbol.removePrefix("$venue:"), root.substringAfter(':')) != null
    }

    companion object {
        /** The broker prefix of option root feeds. */
        const val PREFIX = "OPTIONS:"

        /** [qktSymbol] as an option root feed, or a failure saying what is malformed. */
        fun parse(qktSymbol: String): Result<OptionRootSymbol> =
            runCatching {
                require(qktSymbol.startsWith(PREFIX)) { "not an option root feed: $qktSymbol" }
                val parts = qktSymbol.removePrefix(PREFIX).split('.')
                require(parts.size == 2 && parts.none { it.isEmpty() }) { "$qktSymbol must be OPTIONS:<VENUE>.<ROOT>" }
                OptionRootSymbol("${parts[0]}:${parts[1]}")
            }
    }
}
