package com.qkt.indicators.range

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.RefreshTrigger
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

class RangeAggregateIndicatorTest {
    private val day15 = Instant.parse("2024-01-15T00:00:00Z")
    private val clock = FixedClock(time = day15.plusSeconds(60).toEpochMilli())

    @Suppress("unused")
    private val cal = TradingCalendar.crypto()

    private fun candle(
        price: String,
        ts: Long,
    ) = Candle("X", Money.of(price), Money.of(price), Money.of(price), Money.of(price), Money.ZERO, ts, ts + 60_000L)

    private class FakeSource(
        private val seq: Sequence<Candle>,
    ) : MarketSource {
        override val name = "Fake"
        override val capabilities = setOf(MarketSourceCapability.BARS)

        override fun supports(symbol: String): Boolean = true

        override fun bars(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ): Sequence<Candle> = seq
    }

    @Test
    fun `caches reduce result and updates on refresh trigger`() {
        var calls = 0
        val source =
            FakeSource(
                generateSequence(0L) { it + 1 }
                    .take(3)
                    .map { i -> candle((100 + i).toString(), day15.plusSeconds(i).toEpochMilli()) },
            )
        val indicator =
            RangeAggregateIndicator(
                symbol = "X",
                window = TimeWindow.ONE_MINUTE,
                rangeSpec = {
                    calls++
                    TimeRange(day15.minusSeconds(60), day15)
                },
                reduce = { it.maxOfOrNull { c -> c.high } },
                source = source,
                clock = clock,
                refreshOn = RefreshTrigger.Once,
            )

        indicator.update(Tick("X", Money.of("100"), day15.toEpochMilli()))
        indicator.update(Tick("X", Money.of("100"), day15.plusSeconds(1).toEpochMilli()))
        indicator.update(Tick("X", Money.of("100"), day15.plusSeconds(2).toEpochMilli()))

        assertThat(indicator.value()).isEqualByComparingTo(Money.of("102"))
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `EveryNTicks refresh recomputes every n ticks`() {
        var calls = 0
        val source =
            FakeSource(
                sequenceOf(candle("100", day15.toEpochMilli())),
            )
        val indicator =
            RangeAggregateIndicator(
                symbol = "X",
                window = TimeWindow.ONE_MINUTE,
                rangeSpec = {
                    calls++
                    TimeRange(day15.minusSeconds(60), day15)
                },
                reduce = { it.maxOfOrNull { c -> c.high } },
                source = source,
                clock = clock,
                refreshOn = RefreshTrigger.EveryNTicks(3),
            )

        repeat(7) { i ->
            indicator.update(Tick("X", Money.of("100"), day15.toEpochMilli() + i))
        }

        assertThat(calls).isEqualTo(3)
    }

    @Test
    fun `value is null until first update`() {
        val source = FakeSource(sequenceOf(candle("100", day15.toEpochMilli())))
        val indicator =
            RangeAggregateIndicator(
                symbol = "X",
                window = TimeWindow.ONE_MINUTE,
                rangeSpec = { TimeRange(day15.minusSeconds(60), day15) },
                reduce = { it.maxOfOrNull { c -> c.high } },
                source = source,
                clock = clock,
                refreshOn = RefreshTrigger.Once,
            )
        assertThat(indicator.value()).isNull()
        assertThat(indicator.isReady).isFalse()
    }

    @Test
    fun `isReady becomes true after a successful reduce`() {
        val source = FakeSource(sequenceOf(candle("100", day15.toEpochMilli())))
        val indicator =
            RangeAggregateIndicator(
                symbol = "X",
                window = TimeWindow.ONE_MINUTE,
                rangeSpec = { TimeRange(day15.minusSeconds(60), day15) },
                reduce = { it.maxOfOrNull { c -> c.high } },
                source = source,
                clock = clock,
                refreshOn = RefreshTrigger.Once,
            )
        indicator.update(Tick("X", Money.of("100"), day15.toEpochMilli()))
        assertThat(indicator.isReady).isTrue()
        assertThat(indicator.value() as BigDecimal).isEqualByComparingTo(Money.of("100"))
    }
}
