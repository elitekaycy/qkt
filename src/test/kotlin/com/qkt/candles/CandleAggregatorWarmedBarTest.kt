package com.qkt.candles

import com.qkt.common.Money
import com.qkt.events.TickEvent
import com.qkt.events.WarmupTickEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.candleToTicks
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A live feed's first quote can be older than the last bar warmup replayed (a venue's cached quote
 * snapshot on subscribe, #1525): that bar is warmup's, so the quote never joins it.
 */
class CandleAggregatorWarmedBarTest : CandleAggregatorFixture() {
    private val minute = 60_000L
    private val n = 29_886_953L * minute

    private fun bar(
        start: Long,
        close: String,
    ) = Candle(
        "X",
        Money.of("100"),
        Money.of("101"),
        Money.of("99"),
        Money.of(close),
        Money.of("2"),
        start,
        start + minute,
    )

    private fun warm(vararg bars: Candle) =
        bars.flatMap(::candleToTicks).forEach { bus.publish(WarmupTickEvent(it, sourceTimeframeMs = minute)) }

    @Test
    fun `a live tick stamped inside the last warmed bar leaves that bar as warmup closed it`() {
        aggregator()
        warm(bar(n - 2 * minute, "100.1"), bar(n - minute, "100.2"), bar(n, "100.3"))

        bus.publish(
            TickEvent(Tick("X", Money.of("100.9"), n + 37_476L, bid = Money.of("100.8"), ask = Money.of("101"))),
        )
        publishTick("X", Money.of("100.5"), n + minute + 27_479L)
        publishTick("X", Money.of("100.6"), n + 2 * minute)

        val closed = captured.map { it.candle }
        assertThat(closed.map { it.startTime }).containsExactly(n - 2 * minute, n - minute, n, n + minute)
        assertThat(closed[2].close).isEqualByComparingTo("100.3")
        assertThat(closed[2].high).isEqualByComparingTo("101")
        assertThat(listOf(closed[2].bid, closed[2].ask)).containsOnlyNulls()
        assertThat(closed[3].open).isEqualByComparingTo("100.5")
    }

    @Test
    fun `a tick warmup covered is counted as covered, not as one the venue's bar lacks`() {
        val agg = aggregator()
        warm(bar(n, "100.3"))

        publishTick("X", Money.of("100.9"), n + 37_476L)

        assertThat(agg.historyCoveredTicks).isEqualTo(1L)
        assertThat(agg.droppedLateTicks).isZero()
    }

    @Test
    fun `warmup ticks without a source bar cover nothing, so live ticks after them still aggregate`() {
        aggregator()
        bus.publish(WarmupTickEvent(Tick("X", Money.of("100"), n + 1_000L)))

        publishTick("X", Money.of("102"), n + 2_000L)
        publishTick("X", Money.of("103"), n + minute)

        assertThat(captured.single().candle.close).isEqualByComparingTo("102")
    }
}
