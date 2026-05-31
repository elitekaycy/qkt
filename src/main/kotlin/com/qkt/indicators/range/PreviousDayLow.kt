package com.qkt.indicators.range

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.SessionAnchor
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketSource
import java.math.BigDecimal

/**
 * Low of the previous trading day (per [calendar]). Mirrors [PreviousDayHigh] —
 * stable across the current trading day, used as a breakdown reference.
 */
class PreviousDayLow(
    symbol: String,
    calendar: TradingCalendar,
    source: MarketSource,
    clock: Clock,
    window: TimeWindow = TimeWindow.ONE_MINUTE,
) : SessionAnchoredIndicator<BigDecimal>(
        anchor = SessionAnchor.PreviousDay,
        calendar = calendar,
        symbol = symbol,
        window = window,
        source = source,
        clock = clock,
        reduce = { it.minOfOrNull { c -> c.low } },
    )
