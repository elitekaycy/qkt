package com.qkt.marketdata.live.tv

import com.qkt.candles.TimeWindow
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.UnsupportedDataException

object TradingViewResolution {
    private val SUPPORTED: Map<Long, String> =
        linkedMapOf(
            1_000L to "1S",
            5_000L to "5S",
            15_000L to "15S",
            30_000L to "30S",
            60_000L to "1",
            300_000L to "5",
            900_000L to "15",
            1_800_000L to "30",
            3_600_000L to "60",
            14_400_000L to "240",
            86_400_000L to "1D",
            604_800_000L to "1W",
        )

    fun fromTimeWindow(window: TimeWindow): String =
        SUPPORTED[window.durationMs]
            ?: throw UnsupportedDataException(
                MarketSourceCapability.BARS,
                "TradingViewMarketSource does not support window ${window.durationMs}ms; supported windows (ms): ${SUPPORTED.keys}",
            )

    fun supportedWindows(): List<TimeWindow> = SUPPORTED.keys.map { TimeWindow(it) }
}
