package com.qkt.cli

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.CachedHistoricalMarketSource
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
    fun `composite routes an mt5 account prefix to its feed`() {
        val accounts = TestAccounts.directory(TestAccounts.mt5("exness", gatewayUrl = "http://example"))
        val factory = MarketSourceFactory.composite(accounts.marketDataRoutes()) { StubFallback() }
        val composite = factory(emptyList())
        assertThat(composite.supports("EXNESS:XAUUSD")).isTrue
        val (_, feed) = accounts.marketDataRoutes().single()
        assertThat(feed).isInstanceOf(CachedHistoricalMarketSource::class.java)
    }

    @Test
    fun `composite routes BYBIT prefixes when bybit accounts are configured`() {
        val accounts = TestAccounts.directory(TestAccounts.bybit("spot"), TestAccounts.bybit("linear"))
        val factory = MarketSourceFactory.composite(accounts.marketDataRoutes(), source = "local")
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
    fun `with no accounts, EXNESS prefix delegates to fallback`() {
        val factory = MarketSourceFactory.composite(emptyList()) { StubFallback() }
        val composite = factory(emptyList())
        // No account routes registered; EXNESS: falls through to fallback (StubFallback)
        // StubFallback.supports returns true so EXNESS:XAUUSD is accepted by the composite.
        assertThat(composite.supports("EXNESS:XAUUSD")).isTrue
    }
}
