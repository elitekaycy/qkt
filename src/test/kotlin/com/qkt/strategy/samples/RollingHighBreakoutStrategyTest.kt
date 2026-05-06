package com.qkt.strategy.samples

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.Mode
import com.qkt.strategy.SessionContext
import com.qkt.strategy.Signal
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RollingHighBreakoutStrategyTest {
    private val now = Instant.parse("2024-01-15T15:00:00Z")

    private fun candle(
        high: String,
        startMs: Long,
    ): Candle =
        Candle(
            symbol = "X",
            open = Money.of(high),
            high = Money.of(high),
            low = Money.of(high),
            close = Money.of(high),
            volume = Money.of("1"),
            startTime = startMs,
            endTime = startMs + 3_600_000L,
        )

    private fun fakeSource(rangeHigh: String): MarketSource =
        object : MarketSource {
            override val name = "Fake"
            override val capabilities = setOf(MarketSourceCapability.BARS)

            override fun supports(symbol: String): Boolean = true

            override fun bars(
                symbol: String,
                window: TimeWindow,
                range: TimeRange,
            ): Sequence<Candle> = sequenceOf(candle(rangeHigh, range.from.toEpochMilli()))
        }

    private fun ctx(rangeHigh: String): SessionContext =
        SessionContext(
            mode = Mode.BACKTEST,
            clock = FixedClock(time = now.toEpochMilli()),
            calendar = TradingCalendar.crypto(),
            source = fakeSource(rangeHigh),
        )

    @Test
    fun `emits Buy when price exceeds rolling window high`() {
        val emitted = mutableListOf<Signal>()
        val strategy = RollingHighBreakoutStrategy(symbol = "X")
        strategy.onTickWithContext(
            tick = Tick("X", Money.of("130"), now.toEpochMilli()),
            ctx = ctx("125"),
            emit = { emitted.add(it) },
        )
        assertThat(emitted).containsExactly(Signal.Buy("X", Money.of("1")))
    }

    @Test
    fun `does not emit when price is below rolling window high`() {
        val emitted = mutableListOf<Signal>()
        val strategy = RollingHighBreakoutStrategy(symbol = "X")
        strategy.onTickWithContext(
            tick = Tick("X", Money.of("120"), now.toEpochMilli()),
            ctx = ctx("125"),
            emit = { emitted.add(it) },
        )
        assertThat(emitted).isEmpty()
    }

    @Test
    fun `emits at most once per session`() {
        val emitted = mutableListOf<Signal>()
        val strategy = RollingHighBreakoutStrategy(symbol = "X", lookback = Duration.ofDays(3))
        val context = ctx("125")
        strategy.onTickWithContext(Tick("X", Money.of("130"), now.toEpochMilli()), context, { emitted.add(it) })
        strategy.onTickWithContext(
            Tick("X", Money.of("131"), now.toEpochMilli() + 1_000L),
            context,
            { emitted.add(it) },
        )
        assertThat(emitted).hasSize(1)
    }
}
