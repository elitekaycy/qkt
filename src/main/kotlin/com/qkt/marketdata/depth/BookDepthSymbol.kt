package com.qkt.marketdata.depth

import java.math.BigDecimal

/**
 * The hidden streams a strategy's depth fields read: `DEPTH:<SIDE>:<VENUE>:<NAME>`, one value of contract
 * `<VENUE>:<NAME>`'s book per snapshot. `<alias>.bid_depth` reads `DEPTH:BID:…` ([BookDepth.bidDepth]),
 * `.ask_depth` reads `DEPTH:ASK:…` ([BookDepth.askDepth]) and `.book_imbalance` reads `DEPTH:IMBALANCE:…`
 * ([BookDepth.imbalance]). The DSL rewrites each field into its stream
 * ([com.qkt.dsl.compile.BookDepthFieldExpansion]); a strategy never declares one by hand.
 */
object BookDepthSymbol {
    /** The broker prefix of depth streams. */
    const val BROKER: String = "DEPTH"

    /** [BROKER] with its separator, as symbols start. */
    const val PREFIX: String = "$BROKER:"

    /** Each stream field to the part of the stream symbol naming it. */
    private val PARTS: Map<String, String> =
        mapOf(
            "bid_depth" to "BID",
            "ask_depth" to "ASK",
            "book_imbalance" to "IMBALANCE",
        )

    /** The stream fields that read a contract's book, `<alias>.bid_depth` and the rest. */
    val FIELDS: List<String> = PARTS.keys.toList()

    /** The depth-stream symbol of [field] (one of [FIELDS]) of contract [qktSymbol], without [PREFIX]. */
    fun symbolOf(
        field: String,
        qktSymbol: String,
    ): String = "${PARTS.getValue(field)}:$qktSymbol"

    /** The depth stream of [field] of contract [qktSymbol]. */
    fun of(
        field: String,
        qktSymbol: String,
    ): String = PREFIX + symbolOf(field, qktSymbol)

    /** The contract whose book [symbol] reads, or null when [symbol] is not a depth stream. */
    fun contract(symbol: String): String? = parse(symbol)?.second

    /** [symbol]'s value of [depth]: its side's depth or the book's imbalance. */
    fun value(
        symbol: String,
        depth: BookDepth,
    ): BigDecimal =
        when (parse(symbol)?.first) {
            "BID" -> depth.bidDepth
            "ASK" -> depth.askDepth
            "IMBALANCE" -> depth.imbalance
            else -> throw IllegalArgumentException(
                "$symbol is not a depth stream (DEPTH:<BID|ASK|IMBALANCE>:<VENUE>:<NAME>)",
            )
        }

    private fun parse(symbol: String): Pair<String, String>? {
        if (!symbol.startsWith(PREFIX)) return null
        val part = symbol.removePrefix(PREFIX).substringBefore(':')
        val contract = symbol.removePrefix(PREFIX).substringAfter(':', "")
        return (part to contract).takeIf { part in PARTS.values && ':' in contract && !contract.startsWith(':') }
    }
}
