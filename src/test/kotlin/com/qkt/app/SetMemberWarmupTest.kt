package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.dsl.compile.HubKey
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.NullMarketSource
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SetMemberWarmupTest {
    private val minute = 60_000L
    private val t0 = 1_700_000_040_000L
    private val key = HubKey("GW", "A", "1m")

    /** One flat bar a minute of GW:A over the last hour before [t0]. */
    private val source =
        object : MarketSource {
            override val name = "Bars"
            override val capabilities = setOf(MarketSourceCapability.BARS)

            override fun supports(symbol: String) = true

            override fun bars(
                symbol: String,
                window: TimeWindow,
                range: TimeRange,
            ): Sequence<Candle> =
                (1..60).asSequence().map { i ->
                    val start = t0 - i * minute
                    val one = BigDecimal.ONE
                    Candle(symbol, one, one, one, one, one, start, start + minute)
                }
        }

    @Test
    fun `a joiner reads at most the bars it lacks, all ending by the bound`() {
        val bars = setMemberWarmup(source)(key, 5, t0 - 10 * minute)
        assertThat(bars).hasSize(5)
        assertThat(bars.map { it.endTime }).allMatch { it <= t0 - 10 * minute }
        assertThat(bars.last().endTime).isEqualTo(t0 - 10 * minute)
    }

    @Test
    fun `a source without bars gives a joiner none`() {
        assertThat(setMemberWarmup(NullMarketSource)(key, 5, t0)).isEmpty()
    }

    @Test
    fun `a history read that fails gives a joiner no bars instead of stopping the session`() {
        val failing =
            object : MarketSource by source {
                override fun bars(
                    symbol: String,
                    window: TimeWindow,
                    range: TimeRange,
                ): Sequence<Candle> = error("venue timed out")
            }
        assertThat(setMemberWarmup(failing)(key, 5, t0)).isEmpty()
    }
}
