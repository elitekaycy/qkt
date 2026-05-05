package com.qkt.marketdata.source

import java.time.Instant

data class MarketRequest(
    val symbols: List<String>,
    val from: Instant? = null,
    val to: Instant? = null,
) {
    init {
        require(symbols.isNotEmpty()) { "MarketRequest requires at least one symbol" }
        if (from != null && to != null) {
            require(from < to) { "from must be < to: from=$from, to=$to" }
        }
    }
}
