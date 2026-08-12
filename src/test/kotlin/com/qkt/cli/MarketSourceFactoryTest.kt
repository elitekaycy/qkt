package com.qkt.cli

import com.qkt.broker.mt5.MT5BrokerProfile
import com.qkt.broker.mt5.SymbolCalendars
import com.qkt.broker.mt5.SymbolPolicy
import com.qkt.common.TradingCalendar
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

    @Test
    fun `composite routes cataloged policy rates but rejects arbitrary macro live series`() {
        val composite =
            MarketSourceFactory.composite(
                mt5Profiles = emptyList(),
                source = "local",
                enableBybit = false,
            )(emptyList())

        assertThat(composite.supports("MACRO:RBA_RBNZ_RATE_DIFF")).isTrue()
        assertThat(composite.supports("MACRO:DFII10")).isFalse()
    }

    @Test
    fun `profiles identical except name and magic share one market-data group`() {
        val groups =
            MarketSourceFactory.groupByMarketDataIdentity(
                listOf(
                    profile("exness_s0", magic = 100),
                    profile("exness_s1", magic = 101),
                    profile("exness_s2", magic = 102),
                ),
            )

        assertThat(groups).hasSize(1)
        assertThat(groups.single().map { it.name })
            .containsExactly("exness_s0", "exness_s1", "exness_s2")
    }

    @Test
    fun `profiles differing in any market-data field keep their own groups`() {
        val base = profile("exness_s0", magic = 100)

        val byGateway =
            MarketSourceFactory.groupByMarketDataIdentity(
                listOf(base, profile("exness_s1", magic = 101, gatewayUrl = "http://other-gateway:8080")),
            )
        assertThat(byGateway).hasSize(2)

        val bySuffix =
            MarketSourceFactory.groupByMarketDataIdentity(
                listOf(base, profile("exness_s1", magic = 101, suffix = "")),
            )
        assertThat(bySuffix).hasSize(2)

        val byPollInterval =
            MarketSourceFactory.groupByMarketDataIdentity(
                listOf(base, profile("exness_s1", magic = 101).copy(tickPollIntervalMs = 2000)),
            )
        assertThat(byPollInterval).hasSize(2)

        val byCalendar =
            MarketSourceFactory.groupByMarketDataIdentity(
                listOf(
                    base,
                    profile("exness_s1", magic = 101).copy(
                        symbolCalendars =
                            SymbolCalendars(
                                listOf(SymbolCalendars.Rule("BTC*", TradingCalendar.crypto())),
                                TradingCalendar.fxDefault(),
                            ),
                    ),
                ),
            )
        assertThat(byCalendar).hasSize(2)
    }

    @Test
    fun `composite serves every profile prefix of a shared group but no unconfigured prefix`() {
        val composite =
            MarketSourceFactory.composite(
                mt5Profiles = listOf(profile("exness_s0", magic = 100), profile("exness_s1", magic = 101)),
                source = "local",
                enableBybit = false,
            )(emptyList())

        assertThat(composite.supports("EXNESS_S0:EURUSD")).isTrue()
        assertThat(composite.supports("EXNESS_S1:EURUSD")).isTrue()
        assertThat(composite.supports("EXNESS_S2:EURUSD")).isFalse()
    }

    private fun profile(
        name: String,
        magic: Int,
        gatewayUrl: String = "http://gateway:8080",
        suffix: String = "m",
    ): MT5BrokerProfile =
        MT5BrokerProfile(
            name = name,
            gatewayUrl = gatewayUrl,
            symbolPolicy = SymbolPolicy(suffix = suffix),
            magic = magic,
        )
}
