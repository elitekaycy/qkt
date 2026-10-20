package com.qkt.marketdata.ingest

/** Presets that recognise a real source only to refuse it: preset to its sample and the text the refusal must contain. */
object RefusedSampleCases {
    val all: Map<String, Pair<String, String>> =
        mapOf(
            "mt5-bars-daily" to ("EURUSD_D1.csv" to "qkt's daily bars are UTC days"),
            // A real broker's 4h .hst file: its weekly opens (17:00 New York, as FX opens) leave only clocks that agree
            // over its dates, and its bars start at the broker's 22:00Z open, off the UTC 4h grid qkt stores.
            "mt4-hst" to ("EURUSD240.hst" to "past the UTC 240-minute grid"),
        )
}
