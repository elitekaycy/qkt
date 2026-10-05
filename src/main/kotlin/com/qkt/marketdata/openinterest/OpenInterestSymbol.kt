package com.qkt.marketdata.openinterest

/**
 * The hidden stream a strategy's `<alias>.open_interest` reads: `OI:<VENUE>:<NAME>`, the open interest of
 * the traded contract `<VENUE>:<NAME>`. The DSL rewrites the field into this stream
 * ([com.qkt.dsl.compile.OpenInterestFieldExpansion]); a strategy never declares it by hand.
 */
object OpenInterestSymbol {
    /** The broker prefix of open-interest streams. */
    const val BROKER: String = "OI"

    /** [BROKER] with its separator, as symbols start. */
    const val PREFIX: String = "$BROKER:"

    /** The stream field that reads it, `<alias>.open_interest`. */
    const val FIELD: String = "open_interest"

    /** The open-interest stream of contract [qktSymbol]. */
    fun of(qktSymbol: String): String = PREFIX + qktSymbol

    /** The contract whose open interest [symbol] carries, or null when [symbol] is not an open-interest stream. */
    fun contract(symbol: String): String? =
        symbol.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { ':' in it && !it.startsWith(':') }
}
