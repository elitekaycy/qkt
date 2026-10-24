package com.qkt.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DaemonCommandCalendarTest {
    @Test
    fun `live calendar resolver honors venue rules and broker-agnostic fallbacks`() {
        val accounts =
            TestAccounts.directory(
                TestAccounts.mt5("venue_a", tradingHours = listOf("BTC*" to "crypto")),
                TestAccounts.bybit("linear"),
            )

        val calendars = CalendarResolver.live(accounts, null)
        assertThat(calendars.calendarFor("VENUE_A:BTCUSD").name).isEqualTo("crypto")
        assertThat(calendars.calendarFor("VENUE_A:EURUSD").name).startsWith("fx")
        assertThat(calendars.calendarFor("BYBIT_LINEAR:BTCUSDT").name).isEqualTo("crypto")
        assertThat(calendars.calendarFor("PAPER:SPX").name).isEqualTo("nyse")
    }
}
