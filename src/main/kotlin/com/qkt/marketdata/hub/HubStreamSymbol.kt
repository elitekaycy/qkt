package com.qkt.marketdata.hub

/**
 * The hidden per-field stream symbol a hub alias expands into: `HUB:<dataset>[.<scope>]/<field>`.
 *
 * Encoding the scope in the symbol rather than resolving it at evaluation time keeps the engine's
 * routing a pure string match, which is what lets a hub stream flow through the existing merge and
 * candle machinery without any of it knowing what a scope is.
 */
data class HubStreamSymbol(
    val dataset: String,
    val scope: String?,
    val field: String,
) {
    /** The routing symbol the engine matches on, e.g. `HUB:cal.high_impact.USD/surprise`. */
    val symbol: String = HubMarketSource.PREFIX + dataset + (if (scope != null) ".$scope" else "") + "/" + field

    companion object {
        fun parse(symbol: String): HubStreamSymbol {
            require(symbol.startsWith(HubMarketSource.PREFIX)) { "not a hub symbol: $symbol" }
            val body = symbol.removePrefix(HubMarketSource.PREFIX)
            val slash = body.indexOf('/')
            require(slash > 0 && slash < body.length - 1) {
                "hub symbol must be HUB:<dataset>[.<scope>]/<field>, got $symbol"
            }
            val field = body.substring(slash + 1)
            val head = body.substring(0, slash)
            val segments = head.split('.')
            // A dataset name is lowercase dotted; a scope is upper case (USD, ALL, EXNESS:XAUUSD).
            // Splitting on that rather than on position means a dataset may keep any number of
            // segments without the parser having to know how many.
            val last = segments.last()
            return if (segments.size > 2 && last == last.uppercase() && last.any { it.isLetter() }) {
                HubStreamSymbol(segments.dropLast(1).joinToString("."), last, field)
            } else {
                HubStreamSymbol(head, null, field)
            }
        }
    }
}
