package com.qkt.cli

import com.qkt.broker.mt5.MT5DefaultProfiles
import com.qkt.broker.mt5.SymbolCalendars
import com.qkt.common.TradingCalendar
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DaemonCommandCalendarTest {
    @Test
    fun `live calendar resolver honors venue rules and broker-agnostic fallbacks`() {
        val profile =
            MT5DefaultProfiles.exness.copy(
                name = "venue_a",
                symbolCalendars =
                    SymbolCalendars(
                        rules = listOf(SymbolCalendars.Rule("BTC*", TradingCalendar.crypto())),
                        default = TradingCalendar.fxDefault(),
                    ),
            )

        assertThat(liveCalendarFor("VENUE_A:BTCUSD", listOf(profile)).name).isEqualTo("crypto")
        assertThat(liveCalendarFor("VENUE_A:EURUSD", listOf(profile)).name).isEqualTo("fx")
        assertThat(liveCalendarFor("BYBIT_LINEAR:BTCUSDT", listOf(profile)).name).isEqualTo("crypto")
        assertThat(liveCalendarFor("PAPER:SPX", listOf(profile)).name).isEqualTo("nyse")
    }
}
