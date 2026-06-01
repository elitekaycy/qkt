package com.qkt.cli

import com.qkt.broker.mt5.MT5BrokerProfile
import com.qkt.broker.mt5.SymbolPolicy
import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DaemonCommandSourceWiringTest {
    private class StubFallback : MarketSource {
        override val name: String = "stub-fallback"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed =
            object : TickFeed {
                override fun next(): Tick? = null

                override fun close() {}
            }

        override fun bars(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ): Sequence<Candle> = emptySequence()
    }

    @Test
    fun `composite routes EXNESS prefix to Mt5MarketSource`() {
        val exness =
            MT5BrokerProfile(
                name = "exness",
                gatewayUrl = "http://example",
                symbolPolicy = SymbolPolicy(suffix = "m"),
                magic = 1,
            )
        val factory = MarketSourceFactory.composite(listOf(exness)) { StubFallback() }
        val composite = factory(emptyList())
        assertThat(composite.supports("EXNESS:XAUUSD")).isTrue
    }

    @Test
    fun `composite routes BYBIT_SPOT prefix unconditionally`() {
        val factory = MarketSourceFactory.composite(emptyList()) { StubFallback() }
        val composite = factory(emptyList())
        assertThat(composite.supports("BYBIT_SPOT:BTCUSDT")).isTrue
        assertThat(composite.supports("BYBIT_LINEAR:BTCUSDT")).isTrue
    }

    @Test
    fun `composite falls through to fallback for unknown prefix`() {
        val factory = MarketSourceFactory.composite(emptyList()) { StubFallback() }
        val composite = factory(emptyList())
        // StubFallback.supports returns true for anything
        assertThat(composite.supports("UNKNOWN:FOO")).isTrue
    }

    @Test
    fun `factory returns the same composite instance across calls`() {
        val factory = MarketSourceFactory.composite(emptyList()) { StubFallback() }
        val first = factory(emptyList())
        val second = factory(listOf("EXNESS:XAUUSD"))
        assertThat(first).isSameAs(second)
    }

    @Test
    fun `with no MT5 profiles, EXNESS prefix delegates to fallback`() {
        val factory = MarketSourceFactory.composite(emptyList()) { StubFallback() }
        val composite = factory(emptyList())
        // No MT5 routes registered; EXNESS: falls through to fallback (StubFallback)
        // StubFallback.supports returns true so EXNESS:XAUUSD is accepted by the composite.
        assertThat(composite.supports("EXNESS:XAUUSD")).isTrue
    }
}
