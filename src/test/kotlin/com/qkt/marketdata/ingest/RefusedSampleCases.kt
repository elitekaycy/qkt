package com.qkt.marketdata.ingest

/** Presets that recognise a real source only to refuse it: preset to its sample and the text the refusal must contain. */
object RefusedSampleCases {
    val all: Map<String, Pair<String, String>> =
        mapOf(
            "mt5-bars-daily" to ("EURUSD_D1.csv" to "qkt's daily bars are UTC days"),
            // A real broker's .hst file, whose clock it does not say: refused until --tz names it.
            "mt4-hst" to ("EURUSD240.hst" to "MetaTrader files are on the broker's server time"),
        )
}
