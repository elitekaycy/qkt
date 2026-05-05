package com.qkt.strategy

import com.qkt.common.Clock
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketSource

data class SessionContext(
    val mode: Mode,
    val clock: Clock,
    val calendar: TradingCalendar,
    val source: MarketSource,
)
