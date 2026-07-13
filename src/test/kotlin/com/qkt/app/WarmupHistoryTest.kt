package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.InMemoryMarketSource
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WarmupHistoryTest {
    private val window = TimeWindow.ONE_HOUR
    private val upper = Instant.parse("2026-07-13T12:00:00Z").toEpochMilli()

    @Test
    fun `expands the range across a closed-market gap and returns the newest requested bars`() {
        val starts =
            listOf(
                "2026-07-10T09:00:00Z",
                "2026-07-10T10:00:00Z",
                "2026-07-10T11:00:00Z",
                "2026-07-12T22:00:00Z",
                "2026-07-12T23:00:00Z",
                "2026-07-13T00:00:00Z",
                "2026-07-13T01:00:00Z",
                "2026-07-13T02:00:00Z",
                "2026-07-13T03:00:00Z",
                "2026-07-13T04:00:00Z",
                "2026-07-13T05:00:00Z",
                "2026-07-13T06:00:00Z",
                "2026-07-13T07:00:00Z",
                "2026-07-13T08:00:00Z",
                "2026-07-13T09:00:00Z",
                "2026-07-13T10:00:00Z",
                "2026-07-13T11:00:00Z",
            ).map(Instant::parse).map(Instant::toEpochMilli)
        val source = RecordingRangeSource(starts.map(::candle))

        val bars = WarmupHistoryLoader(source).load("X", window, count = 15, upperMs = upper)

        assertThat(source.ranges.size).isGreaterThan(1)
        assertThat(bars).hasSize(15)
        assertThat(bars.first().startTime).isEqualTo(Instant.parse("2026-07-10T11:00:00Z").toEpochMilli())
        assertThat(bars.last().startTime).isEqualTo(Instant.parse("2026-07-13T11:00:00Z").toEpochMilli())
    }

    @Test
    fun `deduplicates and sorts source bars before taking the newest count`() {
        val starts = (7 downTo 1).map { upper - it * window.durationMs }
        val source = RecordingRangeSource((starts + starts.last()).map(::candle))

        val bars = WarmupHistoryLoader(source).load("X", window, count = 5, upperMs = upper)

        assertThat(bars.map { it.startTime }).containsExactlyElementsOf(starts.sorted().takeLast(5))
    }

    @Test
    fun `fails deployment when bounded expansion still cannot fill the request`() {
        val source = RecordingRangeSource(listOf(candle(upper - window.durationMs)))

        assertThatThrownBy { WarmupHistoryLoader(source).load("X", window, count = 2, upperMs = upper) }
            .isInstanceOf(WarmupUnderfilledException::class.java)
            .hasMessageContaining("requested=2 available=1")
            .hasMessageContaining("Deploy aborted")
        assertThat(source.ranges).hasSize(8)
    }

    @Test
    fun `reuses an exact request within one live session`() {
        val source = RecordingRangeSource((1..3).map { candle(upper - it * window.durationMs) })
        val loader = WarmupHistoryLoader(source)

        val first = loader.load("X", window, count = 3, upperMs = upper)
        val second = loader.load("X", window, count = 3, upperMs = upper)

        assertThat(second).isSameAs(first)
        assertThat(source.ranges).hasSize(1)
    }

    private fun candle(startMs: Long): Candle =
        Candle(
            symbol = "X",
            open = Money.of("1"),
            high = Money.of("1"),
            low = Money.of("1"),
            close = Money.of("1"),
            volume = Money.of("1"),
            startTime = startMs,
            endTime = startMs + window.durationMs,
        )

    private class RecordingRangeSource(
        bars: List<Candle>,
    ) : InMemoryMarketSource("recording") {
        val ranges = mutableListOf<TimeRange>()

        init {
            seedBars("X", TimeWindow.ONE_HOUR, bars)
        }

        override fun bars(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ): Sequence<Candle> {
            ranges += range
            return super.bars(symbol, window, range)
        }
    }
}
