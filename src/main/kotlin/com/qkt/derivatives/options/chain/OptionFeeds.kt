package com.qkt.derivatives.options.chain

/**
 * Whether [qktSymbol] is a read-only option feed: a whole root (`OPTIONS:<VENUE>.<ROOT>`) or a chain
 * analytics stream (`CHAIN:...`). Neither is traded, so neither needs a broker or picks a calendar.
 */
fun isOptionFeed(qktSymbol: String): Boolean =
    qktSymbol.startsWith(OptionRootSymbol.PREFIX) || qktSymbol.startsWith(ChainAnalyticsSymbol.PREFIX)
