package com.qkt.broker

/**
 * Opt-in ability of a venue: whether one symbol is in session under its own calendar, where
 * [Broker.marketOpen] answers for the venue as a whole. On an account that trades both, gold is
 * closed at the weekend while BTC is open; the market-data gate needs that per-symbol answer to
 * tell a weekend quote gap from a feed fault.
 */
fun interface SymbolSessionProvider {
    /** True when [symbol] (a qkt symbol, `NAME:` prefix optional) trades at [nowMs]. */
    fun symbolInSession(
        symbol: String,
        nowMs: Long,
    ): Boolean
}
