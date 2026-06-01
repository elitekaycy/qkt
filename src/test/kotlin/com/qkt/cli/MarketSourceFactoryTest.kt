package com.qkt.cli

import com.qkt.marketdata.source.NullMarketSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarketSourceFactoryTest {
    @Test
    fun `composite with source=local uses NullMarketSource fallback for unmatched symbols`() {
        val factory = MarketSourceFactory.composite(mt5Profiles = emptyList(), source = "local")
        val composite = factory(emptyList())
        // Bybit routes are added unconditionally so capabilities still includes LIVE_TICKS/BARS.
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
                mt5Profiles = emptyList(),
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
    fun `composite with enableBybit=false does not route BYBIT_SPOT or BYBIT_LINEAR symbols`() {
        val factory =
            MarketSourceFactory.composite(
                mt5Profiles = emptyList(),
                source = "local",
                enableBybit = false,
            )
        val composite = factory(emptyList())
        // With Bybit routes off, a BYBIT_SPOT: symbol falls to Null fallback and reports unsupported.
        assertThat(composite.supports("BYBIT_SPOT:BTCUSDT")).isFalse()
        assertThat(composite.supports("BYBIT_LINEAR:BTCUSDT")).isFalse()
    }

    @Test
    fun `composite with enableBybit=true routes BYBIT_SPOT and BYBIT_LINEAR symbols`() {
        val factory =
            MarketSourceFactory.composite(
                mt5Profiles = emptyList(),
                source = "local",
                enableBybit = true,
            )
        val composite = factory(emptyList())
        assertThat(composite.supports("BYBIT_SPOT:BTCUSDT")).isTrue()
        assertThat(composite.supports("BYBIT_LINEAR:BTCUSDT")).isTrue()
    }
}
