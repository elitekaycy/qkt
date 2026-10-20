package com.qkt.marketdata.ingest.clock

/**
 * The New York hours a market's week can open in, read on the right clock. FX opens at the 17:00 rollover; spot
 * metals at 18:00 on most venues and with FX on some. A file of one DST season shows a wrong DST rule only through
 * this hour, so the narrower the market's window, the more an import can prove.
 */
enum class MarketOpen(
    val hours: IntRange,
) {
    FX(17..17),
    METAL(17..18),

    /** Anything else qkt checks: a vendor's first tick may come a little late. */
    ANY(16..19),
}
