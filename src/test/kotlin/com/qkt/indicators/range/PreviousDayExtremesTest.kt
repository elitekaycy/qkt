package com.qkt.indicators.range

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PreviousDayExtremesTest {
    private val day15 = Instant.parse("2024-01-15T00:00:00Z")
    private val day14Start = day15.minusSeconds(86_400).toEpochMilli()

    private fun candle(
        open: String,
        high: String,
        low: String,
        close: String,
        startMs: Long,
    ) = Candle(
        "X",
        Money.of(open),
        Money.of(high),
        Money.of(low),
        Money.of(close),
        Money.ZERO,
        startMs,
        startMs + 60_000L,
    )

    private val source =
        object : MarketSource {
            override val name = "Fake"
            override val capabilities = setOf(MarketSourceCapability.BARS)

            override fun supports(symbol: String): Boolean = true

            override fun bars(
                symbol: String,
                window: TimeWindow,
                range: TimeRange,
            ): Sequence<Candle> {
                if (range.from.toEpochMilli() == day14Start) {
                    return sequenceOf(
                        candle("100", "110", "95", "105", day14Start),
                        candle("105", "115", "102", "108", day14Start + 60_000L),
                    )
                }
                return emptySequence()
            }
        }

    @Test
    fun `PreviousDayHigh returns max of all previous-day candle highs`() {
        val clock = FixedClock(time = day15.toEpochMilli())
        val indicator = PreviousDayHigh("X", TradingCalendar.crypto(), source, clock)
        indicator.update(Tick("X", Money.of("120"), clock.time))
        assertThat(indicator.value() as BigDecimal).isEqualByComparingTo(Money.of("115"))
    }

    @Test
    fun `PreviousDayLow returns min of all previous-day candle lows`() {
        val clock = FixedClock(time = day15.toEpochMilli())
        val indicator = PreviousDayLow("X", TradingCalendar.crypto(), source, clock)
        indicator.update(Tick("X", Money.of("80"), clock.time))
        assertThat(indicator.value() as BigDecimal).isEqualByComparingTo(Money.of("95"))
    }
}
