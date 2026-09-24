package com.qkt.marketdata

/**
 * Why [MarketDataGate] judged a symbol's feed unhealthy. [wireName] is the `kind` field the
 * `marketdata.stale` insights event carries, e.g. `"clock_skew"` for a broker tick clock hours
 * off the local clock. Expected gaps (venue closed, scheduled breaks) are not faults.
 */
enum class FeedFault(
    val wireName: String,
) {
    /** No tick for longer than the symbol's staleness threshold while its venue is in session. */
    STALE("stale"),

    /** Broker tick timestamps are further from the local clock than the skew tolerance. */
    CLOCK_SKEW("clock_skew"),

    /** A run of ticks was rejected as outliers (crossed books included). */
    OUTLIER("outlier"),
}
