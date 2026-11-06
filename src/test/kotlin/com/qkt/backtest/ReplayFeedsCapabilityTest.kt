package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ReplayFeedsCapabilityTest {
    private val range = TimeRange(Instant.EPOCH, Instant.ofEpochMilli(10_000))

    private fun source(vararg caps: MarketSourceCapability) =
        object : MarketSource {
            override val name = "fake"
            override val capabilities = caps.toSet()

            override fun supports(symbol: String) = true

            override fun ticks(
                symbol: String,
                range: TimeRange,
            ): Sequence<Tick> = sequenceOf(Tick(symbol, BigDecimal.ONE, 1L))
        }

    private fun replay(
        source: MarketSource,
        forceBars: Boolean,
        window: TimeWindow? = null,
    ) = ReplayFeeds.replay(source, listOf("X"), range, emptyMap(), window, forceBars, { 0 })

    @Test
    fun `a tick source replays its ticks and synthesizes nothing`() {
        val (feed, fills) = replay(source(MarketSourceCapability.TICKS), forceBars = false)

        assertThat(feed.next()?.symbol).isEqualTo("X")
        assertThat(fills.any).isFalse()
    }

    @Test
    fun `forcing bars on a source without bars is refused`() {
        assertThatThrownBy { replay(source(MarketSourceCapability.TICKS), forceBars = true) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("--bars: cannot replay bars for X (source has no BARS capability or no candle window)")
    }

    @Test
    fun `a bars-only source without a candle window is refused`() {
        assertThatThrownBy { replay(source(MarketSourceCapability.BARS), forceBars = false) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("bar-based backtest for X needs a candle window (timeframe) — pass candleWindow")
    }
}
