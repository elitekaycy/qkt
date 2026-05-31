package com.qkt.indicators.range

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.SessionAnchor
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketSource
import java.math.BigDecimal

/**
 * High of the previous trading day (per [calendar]). Refreshes once at each daily
 * session boundary; the value is stable for the entire current trading day. Used
 * as a breakout reference ("close above PDH → momentum entry").
 */
class PreviousDayHigh(
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
        reduce = { it.maxOfOrNull { c -> c.high } },
    )
