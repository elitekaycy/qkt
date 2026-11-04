package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ReplayFeedsMt5BarRefusalTest {
    private val cfd = "EXNESS:BTCUSD"
    private val perpetual = "PAPER:BTC_USDC_PERPETUAL"

    /** The MT5 leg has captured ticks; the gateway leg has only its own bars. */
    private val source =
        object : MarketSource {
            override val name = "golden"
            override val capabilities = setOf(MarketSourceCapability.TICKS, MarketSourceCapability.BARS)

            override fun supports(symbol: String) = true

            override fun ticks(
                symbol: String,
                range: TimeRange,
            ): Sequence<Tick> = if (symbol == cfd) sequenceOf(Tick(cfd, BigDecimal.TEN, 1_000)) else emptySequence()

            override fun bars(
                symbol: String,
                window: TimeWindow,
                range: TimeRange,
            ): Sequence<Candle> =
                if (symbol == perpetual) {
                    sequenceOf(
                        Candle(
                            perpetual,
                            BigDecimal.TEN,
                            BigDecimal.TEN,
                            BigDecimal.ONE,
                            BigDecimal.ONE,
                            BigDecimal.ONE,
                            0,
                            60_000,
                        ),
                    )
                } else {
                    emptySequence()
                }
        }

    private fun replay(
        symbols: List<String>,
        mt5Routed: Set<String>,
    ) = ReplayFeeds.replay(
        source,
        symbols,
        TimeRange(Instant.EPOCH, Instant.ofEpochMilli(120_000)),
        emptyMap(),
        TimeWindow.ONE_MINUTE,
        forceBars = false,
        positionSign = { 0 },
        mt5Routed = mt5Routed,
    )

    @Test
    fun `a gateway leg filling on the exchange simulator may replay its own bars beside an mt5-sim leg on ticks`() {
        val (_, barFills) = replay(listOf(cfd, perpetual), mt5Routed = setOf(cfd))

        assertThat(barFills.at(perpetual)).isTrue()
        assertThat(barFills.at(cfd)).isFalse()
    }

    @Test
    fun `a bar-synthesized symbol routed to mt5-sim is still refused`() {
        assertThatThrownBy { replay(listOf(cfd, perpetual), mt5Routed = setOf(cfd, perpetual)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--broker mt5-sim cannot replay [$perpetual] from bars")
    }
}
