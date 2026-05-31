package com.qkt.indicators.range

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.SessionAnchor
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketSource
import java.math.BigDecimal

/**
 * High of the current session (per [anchor] + [calendar]). Refreshes intra-session
 * as new candles land and rolls over at each anchor boundary. Common reference for
 * "have we broken out of this session's range?" rules.
 */
class SessionHigh(
    symbol: String,
    anchor: SessionAnchor,
    calendar: TradingCalendar,
    source: MarketSource,
    clock: Clock,
    window: TimeWindow = TimeWindow.ONE_MINUTE,
) : SessionAnchoredIndicator<BigDecimal>(
        anchor = anchor,
        calendar = calendar,
        symbol = symbol,
        window = window,
        source = source,
        clock = clock,
        reduce = { it.maxOfOrNull { c -> c.high } },
    )
