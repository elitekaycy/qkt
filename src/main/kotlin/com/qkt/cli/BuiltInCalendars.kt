package com.qkt.cli

import com.qkt.common.SymbolCalendars
import com.qkt.common.TradingCalendar

/**
 * The calendars qkt assumes when no venue says: crypto for `BTC*`, `ETH*` and `*USDT`, NYSE hours for the US index
 * symbols, the FX week for everything else. The last step of [CalendarResolver], and the default for data coverage,
 * which judges a store's days by the data's own market.
 */
internal object BuiltInCalendars {
    val rules: SymbolCalendars =
        SymbolCalendars(
            rules =
                listOf(
                    SymbolCalendars.Rule("BTC*", TradingCalendar.crypto()),
                    SymbolCalendars.Rule("ETH*", TradingCalendar.crypto()),
                    SymbolCalendars.Rule("*USDT", TradingCalendar.crypto()),
                    // US equity index CFDs trade NYSE hours. DXY trades the fx week, so it
                    // falls through to the fxDefault default below.
                    SymbolCalendars.Rule("SPX", TradingCalendar.nyse()),
                    SymbolCalendars.Rule("NDX", TradingCalendar.nyse()),
                    SymbolCalendars.Rule("DJI", TradingCalendar.nyse()),
                    SymbolCalendars.Rule("RUT", TradingCalendar.nyse()),
                ),
            default = TradingCalendar.fxDefault(),
        )
}
