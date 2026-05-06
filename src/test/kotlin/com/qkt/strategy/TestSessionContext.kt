package com.qkt.strategy

import com.qkt.common.Clock
import com.qkt.common.FixedClock
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability

private val emptySource =
    object : MarketSource {
        override val name = "Empty"
        override val capabilities = emptySet<MarketSourceCapability>()

        override fun supports(symbol: String): Boolean = false
    }

fun testSessionContext(
    mode: Mode = Mode.BACKTEST,
    clock: Clock = FixedClock(time = 0L),
    calendar: TradingCalendar = TradingCalendar.crypto(),
    source: MarketSource = emptySource,
): SessionContext = SessionContext(mode = mode, clock = clock, calendar = calendar, source = source)
