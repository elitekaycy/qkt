package com.qkt.broker

/**
 * How many mt5-sim fill prices came from each kind of quote: a tick's own bid/ask ([recorded]), the spread
 * `instruments.yaml` states ([venueSpread]), or a spread synthesised around a mid-only price ([synthetic]).
 * Counted where the price is looked up, which includes the venue's pre-trade checks.
 */
data class FillQuotes(
    val recorded: Long = 0L,
    val venueSpread: Long = 0L,
    val synthetic: Long = 0L,
) {
    operator fun plus(other: FillQuotes): FillQuotes =
        FillQuotes(recorded + other.recorded, venueSpread + other.venueSpread, synthetic + other.synthetic)

    val any: Boolean get() = recorded + venueSpread + synthetic > 0L
}
