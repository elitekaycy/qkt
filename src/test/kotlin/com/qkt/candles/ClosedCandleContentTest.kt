package com.qkt.candles

import com.qkt.common.Money
import com.qkt.events.TickEvent
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ClosedCandleContentTest : CandleAggregatorFixture() {
    @Test
    fun `closed candle has correct OHLC computed from all ticks in the window`() {
        aggregator()
        publishTick("XAUUSD", Money.of("2400.0"), 0L)
        publishTick("XAUUSD", Money.of("2401.5"), 15_000L)
        publishTick("XAUUSD", Money.of("2399.5"), 30_000L)
        publishTick("XAUUSD", Money.of("2400.8"), 45_000L)
        publishTick("XAUUSD", Money.of("2402.0"), 75_000L)
        assertThat(captured).hasSize(1)
        val c = captured[0].candle
        assertThat(c.open).isEqualByComparingTo(Money.of("2400.0"))
        assertThat(c.high).isEqualByComparingTo(Money.of("2401.5"))
        assertThat(c.low).isEqualByComparingTo(Money.of("2399.5"))
        assertThat(c.close).isEqualByComparingTo(Money.of("2400.8"))
    }

    @Test
    fun `closed candle's startTime is window-aligned not first-tick timestamp`() {
        aggregator()
        publishTick("XAUUSD", Money.of("2400.0"), 23_456L)
        publishTick("XAUUSD", Money.of("2401.0"), 75_000L)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].candle.startTime).isEqualTo(0L)
    }

    @Test
    fun `closed candle's endTime is startTime plus durationMs`() {
        aggregator()
        publishTick("XAUUSD", Money.of("2400.0"), 0L)
        publishTick("XAUUSD", Money.of("2401.0"), 75_000L)
        assertThat(captured).hasSize(1)
        val c = captured[0].candle
        assertThat(c.endTime).isEqualTo(c.startTime + 60_000L)
    }

    @Test
    fun `volume sums only non-null tick volumes`() {
        aggregator()
        publishTick("XAUUSD", Money.of("2400.0"), 0L, volume = Money.of("1.5"))
        publishTick("XAUUSD", Money.of("2400.5"), 30_000L, volume = null)
        publishTick("XAUUSD", Money.of("2400.2"), 45_000L, volume = Money.of("2.5"))
        publishTick("XAUUSD", Money.of("2401.0"), 75_000L)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].candle.volume).isEqualByComparingTo(Money.of("4.0"))
    }

    @Test
    fun `closed candle carries bid and ask from the last tick in the window`() {
        aggregator()
        bus.publish(
            TickEvent(Tick("XAUUSD", Money.of("2400.0"), 0L, bid = Money.of("2399.8"), ask = Money.of("2400.2"))),
        )
        bus.publish(
            TickEvent(Tick("XAUUSD", Money.of("2401.0"), 30_000L, bid = Money.of("2400.7"), ask = Money.of("2401.3"))),
        )
        // tick past endTime closes the window
        bus.publish(TickEvent(Tick("XAUUSD", Money.of("2402.0"), 75_000L)))

        assertThat(captured).hasSize(1)
        assertThat(captured[0].candle.bid).isEqualByComparingTo(Money.of("2400.7"))
        assertThat(captured[0].candle.ask).isEqualByComparingTo(Money.of("2401.3"))
    }

    @Test
    fun `closed candle has null bid and ask when ticks carry none`() {
        aggregator()
        publishTick("XAUUSD", Money.of("2400.0"), 0L)
        publishTick("XAUUSD", Money.of("2401.0"), 75_000L)

        assertThat(captured).hasSize(1)
        assertThat(captured[0].candle.bid).isNull()
        assertThat(captured[0].candle.ask).isNull()
    }
}
