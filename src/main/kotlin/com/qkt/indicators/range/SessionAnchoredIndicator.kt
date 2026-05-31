package com.qkt.indicators.range

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.RefreshTrigger
import com.qkt.common.SessionAnchor
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.MarketSource
import java.time.Instant

/**
 * [RangeAggregateIndicator] specialised for session-anchored ranges — refresh on each
 * session boundary defined by the [SessionAnchor] + [TradingCalendar]. Subclasses
 * supply [reduce] and the concrete anchor (London open, New York close, daily UTC, …).
 */
abstract class SessionAnchoredIndicator<T : Any>(
    anchor: SessionAnchor,
    calendar: TradingCalendar,
    symbol: String,
    window: TimeWindow,
    source: MarketSource,
    clock: Clock,
    reduce: (Sequence<Candle>) -> T?,
) : RangeAggregateIndicator<T>(
        symbol = symbol,
        window = window,
        rangeSpec = {
            calendar.rangeFor(anchor, calendar.anchorEpochFor(anchor, Instant.ofEpochMilli(clock.now())))
        },
        reduce = reduce,
        source = source,
        clock = clock,
        refreshOn = RefreshTrigger.OnAnchorRollover(anchor, calendar),
    )
