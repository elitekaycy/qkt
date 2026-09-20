package com.qkt.cli

import com.qkt.marketdata.source.NullMarketSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarketSourceFactoryTest {
    @Test
    fun `composite with source=local uses NullMarketSource fallback for unmatched symbols`() {
        val factory = MarketSourceFactory.composite(accountRoutes = emptyList(), source = "local")
        val composite = factory(emptyList())
        // The key property: a symbol matching no route falls to the Null fallback (not TV), so
        // no TradingView WebSocket opens at construction. NullMarketSource.supports is always false.
        assertThat(composite.supports("EXNESS:XAUUSD")).isFalse()
        assertThat(composite.supports("UNKNOWN_VENUE:FOO")).isFalse()
    }

    @Test
    fun `composite with explicit fallbackProvider override ignores the source field`() {
        var calls = 0
        val factory =
            MarketSourceFactory.composite(
                accountRoutes = emptyList(),
                source = "tv",
                fallbackProvider = {
                    calls++
                    NullMarketSource
                },
            )
        factory(emptyList())
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `without bybit accounts, BYBIT_SPOT and BYBIT_LINEAR symbols are not routed`() {
        val composite = MarketSourceFactory.composite(accountRoutes = emptyList(), source = "local")(emptyList())
        // No Bybit account configured: a BYBIT_SPOT: symbol falls to the Null fallback and reports unsupported.
        assertThat(composite.supports("BYBIT_SPOT:BTCUSDT")).isFalse()
        assertThat(composite.supports("BYBIT_LINEAR:BTCUSDT")).isFalse()
    }

    @Test
    fun `configured bybit accounts route BYBIT_SPOT and BYBIT_LINEAR symbols`() {
        val accounts = TestAccounts.directory(TestAccounts.bybit("spot"), TestAccounts.bybit("linear"))
        val composite = MarketSourceFactory.composite(accounts.marketDataRoutes(), source = "local")(emptyList())
        assertThat(composite.supports("BYBIT_SPOT:BTCUSDT")).isTrue()
        assertThat(composite.supports("BYBIT_LINEAR:BTCUSDT")).isTrue()
    }

    @Test
    fun `composite routes cataloged policy rates but rejects arbitrary macro live series`() {
        val composite =
            MarketSourceFactory.composite(accountRoutes = emptyList(), source = "local")(emptyList())

        assertThat(composite.supports("MACRO:RBA_RBNZ_RATE_DIFF")).isTrue()
        assertThat(composite.supports("MACRO:DFII10")).isFalse()
    }

    @Test
    fun `composite serves every account prefix of a shared feed but no unconfigured prefix`() {
        val accounts =
            TestAccounts.directory(
                TestAccounts.mt5("exness_s0", magic = 100),
                TestAccounts.mt5("exness_s1", magic = 101),
            )
        val composite = MarketSourceFactory.composite(accounts.marketDataRoutes(), source = "local")(emptyList())

        assertThat(composite.supports("EXNESS_S0:EURUSD")).isTrue()
        assertThat(composite.supports("EXNESS_S1:EURUSD")).isTrue()
        assertThat(composite.supports("EXNESS_S2:EURUSD")).isFalse()
    }
}
