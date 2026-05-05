package com.qkt.indicators.range

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.SessionAnchor
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

class SessionAnchoredIndicatorTest {
    private val day15 = Instant.parse("2024-01-15T00:00:00Z")

    private fun candle(
        price: String,
        startMs: Long,
    ) = Candle(
        "X",
        Money.of(price),
        Money.of(price),
        Money.of(price),
        Money.of(price),
        Money.ZERO,
        startMs,
        startMs + 60_000L,
    )

    private class FakeSource(
        private val byRangeStartMs: Map<Long, List<Candle>>,
    ) : MarketSource {
        override val name = "Fake"
        override val capabilities = setOf(MarketSourceCapability.BARS)

        override fun supports(symbol: String): Boolean = true

        override fun bars(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ): Sequence<Candle> = byRangeStartMs[range.from.toEpochMilli()]?.asSequence() ?: emptySequence()
    }

    @Test
    fun `previous-day anchor refreshes when day rolls over`() {
        val day14Start = day15.minusSeconds(86_400).toEpochMilli()
        val day15Start = day15.toEpochMilli()
        val source =
            FakeSource(
                mapOf(
                    day14Start to listOf(candle("110", day14Start)),
                    day15Start to listOf(candle("130", day15Start)),
                ),
            )

        val clock = FixedClock(time = day15.toEpochMilli())
        val indicator =
            object : SessionAnchoredIndicator<BigDecimal>(
                anchor = SessionAnchor.PreviousDay,
                calendar = TradingCalendar.crypto(),
                symbol = "X",
                window = TimeWindow.ONE_MINUTE,
                source = source,
                clock = clock,
                reduce = { it.maxOfOrNull { c -> c.high } },
            ) {}

        indicator.update(Tick("X", Money.of("100"), day15.toEpochMilli()))
        assertThat(indicator.value() as BigDecimal).isEqualByComparingTo(Money.of("110"))

        clock.time = day15.plusSeconds(86_400).toEpochMilli()
        indicator.update(Tick("X", Money.of("100"), clock.time))
        assertThat(indicator.value() as BigDecimal).isEqualByComparingTo(Money.of("130"))
    }
}
