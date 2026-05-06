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
import com.qkt.strategy.WarmupSpec
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BreakoutOfYesterdayHighStrategyTest {
    private val day15 = Instant.parse("2024-01-15T00:00:00Z")
    private val day14Start = Instant.parse("2024-01-14T00:00:00Z").toEpochMilli()

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
            endTime = startMs + 60_000L,
        )

    private fun fakeSource(): MarketSource =
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
                        candle("110", day14Start),
                        candle("115", day14Start + 60_000L),
                        candle("113", day14Start + 120_000L),
                    )
                }
                return emptySequence()
            }
        }

    private fun ctx(): SessionContext =
        SessionContext(
            mode = Mode.BACKTEST,
            clock = FixedClock(time = day15.toEpochMilli()),
            calendar = TradingCalendar.crypto(),
            source = fakeSource(),
        )

    @Test
    fun `emits Buy when price exceeds previous day high`() {
        val emitted = mutableListOf<Signal>()
        val strategy = BreakoutOfYesterdayHighStrategy(symbol = "X", size = Money.of("1"))

        strategy.onTick(
            tick = Tick("X", Money.of("116"), day15.toEpochMilli()),
            ctx = ctx(),
            emit = { emitted.add(it) },
        )

        assertThat(emitted).containsExactly(Signal.Buy("X", Money.of("1")))
    }

    @Test
    fun `does not emit when price is below previous day high`() {
        val emitted = mutableListOf<Signal>()
        val strategy = BreakoutOfYesterdayHighStrategy(symbol = "X")

        strategy.onTick(
            tick = Tick("X", Money.of("113"), day15.toEpochMilli()),
            ctx = ctx(),
            emit = { emitted.add(it) },
        )

        assertThat(emitted).isEmpty()
    }

    @Test
    fun `emits at most once per session day`() {
        val emitted = mutableListOf<Signal>()
        val strategy = BreakoutOfYesterdayHighStrategy(symbol = "X")

        strategy.onTick(Tick("X", Money.of("116"), day15.toEpochMilli()), ctx(), { emitted.add(it) })
        strategy.onTick(
            Tick("X", Money.of("117"), day15.toEpochMilli() + 1_000L),
            ctx(),
            { emitted.add(it) },
        )
        strategy.onTick(
            Tick("X", Money.of("118"), day15.toEpochMilli() + 2_000L),
            ctx(),
            { emitted.add(it) },
        )

        assertThat(emitted).hasSize(1)
    }

    @Test
    fun `warmup spec covers a full prior day of M1 bars`() {
        val strategy = BreakoutOfYesterdayHighStrategy(symbol = "X")
        assertThat(strategy.warmup).isEqualTo(WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 1440))
    }
}
