package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CompositeMarketSourceTest {
    private class FakeSource(
        override val name: String,
        override val capabilities: Set<MarketSourceCapability>,
        private val supportedPrefixes: List<String>,
    ) : MarketSource {
        var lastBarsSymbol: String? = null
        var lastTicksSymbol: String? = null

        override fun supports(symbol: String): Boolean = supportedPrefixes.any { symbol.startsWith(it) }

        override fun bars(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ): Sequence<Candle> {
            lastBarsSymbol = symbol
            return emptySequence()
        }

        override fun ticks(
            symbol: String,
            range: TimeRange,
        ): Sequence<Tick> {
            lastTicksSymbol = symbol
            return emptySequence()
        }
    }

    @Test
    fun `routes bar query to source whose pattern matches`() {
        val tv = FakeSource("TV", setOf(MarketSourceCapability.BARS), listOf("OANDA:", "BINANCE:"))
        val local = FakeSource("Local", setOf(MarketSourceCapability.BARS, MarketSourceCapability.TICKS), listOf(""))
        val composite =
            CompositeMarketSource(
                routes = listOf(SymbolPattern.prefix("OANDA:") to tv),
                fallback = local,
            )

        val range = TimeRange(Instant.parse("2024-01-15T00:00:00Z"), Instant.parse("2024-01-16T00:00:00Z"))
        composite.bars("OANDA:EURUSD", TimeWindow.ONE_MINUTE, range).toList()

        assertThat(tv.lastBarsSymbol).isEqualTo("OANDA:EURUSD")
        assertThat(local.lastBarsSymbol).isNull()
    }

    @Test
    fun `falls back when no pattern matches`() {
        val tv = FakeSource("TV", setOf(MarketSourceCapability.BARS), listOf("OANDA:"))
        val local = FakeSource("Local", setOf(MarketSourceCapability.BARS, MarketSourceCapability.TICKS), listOf(""))
        val composite =
            CompositeMarketSource(
                routes = listOf(SymbolPattern.prefix("OANDA:") to tv),
                fallback = local,
            )

        val range = TimeRange(Instant.parse("2024-01-15T00:00:00Z"), Instant.parse("2024-01-16T00:00:00Z"))
        composite.bars("EURUSD", TimeWindow.ONE_MINUTE, range).toList()

        assertThat(local.lastBarsSymbol).isEqualTo("EURUSD")
        assertThat(tv.lastBarsSymbol).isNull()
    }

    @Test
    fun `capabilities is the union of all routes plus fallback`() {
        val tv =
            FakeSource("TV", setOf(MarketSourceCapability.LIVE_TICKS, MarketSourceCapability.BARS), listOf("OANDA:"))
        val local = FakeSource("Local", setOf(MarketSourceCapability.BARS, MarketSourceCapability.TICKS), listOf(""))
        val composite =
            CompositeMarketSource(
                routes = listOf(SymbolPattern.prefix("OANDA:") to tv),
                fallback = local,
            )

        assertThat(composite.capabilities).containsExactlyInAnyOrder(
            MarketSourceCapability.LIVE_TICKS,
            MarketSourceCapability.BARS,
            MarketSourceCapability.TICKS,
        )
    }

    @Test
    fun `supports returns true if any route or fallback supports`() {
        val tv = FakeSource("TV", setOf(MarketSourceCapability.BARS), listOf("OANDA:"))
        val local = FakeSource("Local", setOf(MarketSourceCapability.TICKS), listOf("LOCAL:"))
        val composite =
            CompositeMarketSource(
                routes = listOf(SymbolPattern.prefix("OANDA:") to tv),
                fallback = local,
            )

        assertThat(composite.supports("OANDA:EURUSD")).isTrue()
        assertThat(composite.supports("LOCAL:X")).isTrue()
        assertThat(composite.supports("UNKNOWN")).isFalse()
    }

    @Test
    fun `live ticks throws when neither route nor fallback supports it`() {
        val barsOnly = FakeSource("BarsOnly", setOf(MarketSourceCapability.BARS), listOf(""))
        val composite = CompositeMarketSource(routes = emptyList(), fallback = barsOnly)
        assertThatThrownBy { composite.liveTicks(listOf("X")) }
            .isInstanceOf(UnsupportedDataException::class.java)
    }
}
