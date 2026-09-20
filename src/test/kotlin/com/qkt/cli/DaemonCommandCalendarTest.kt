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

        assertThat(liveCalendarFor("VENUE_A:BTCUSD", accounts).name).isEqualTo("crypto")
        assertThat(liveCalendarFor("VENUE_A:EURUSD", accounts).name).isEqualTo("fx")
        assertThat(liveCalendarFor("BYBIT_LINEAR:BTCUSDT", accounts).name).isEqualTo("crypto")
        assertThat(liveCalendarFor("PAPER:SPX", accounts).name).isEqualTo("nyse")
    }
}
