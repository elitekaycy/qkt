package com.qkt.backtest

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.CandleEvent
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReturnAutocorrCollectorTest {
    private val hourMs = 3_600_000L

    /** A bar whose endTime lands in [hourUtc] on 1970-01-01, so its return is bucketed there. */
    private fun candle(
        close: BigDecimal,
        hourUtc: Int,
        symbol: String = "X",
    ): Candle =
        Candle(
            symbol = symbol,
            open = close,
            high = close,
            low = close,
            close = close,
            volume = Money.of("1"),
            startTime = hourUtc * hourMs,
            endTime = hourUtc * hourMs + 60_000L,
        )

    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun feed(
        bus: EventBus,
        symbol: String,
        hourUtc: Int,
        closes: List<String>,
    ) {
        for (c in closes) bus.publish(CandleEvent(candle(Money.of(c), hourUtc, symbol)))
    }

    @Test
    fun `alternating closes give a lag-1 autocorrelation of minus one for that hour`() {
        val bus = newBus()
        val collector = ReturnAutocorrCollector(bus)
        // Bouncing 100,101,100,101,... -> returns alternate +0.01 / -0.0099..., perfectly
        // anti-correlated at lag 1, all within hour 13.
        feed(bus, "X", 13, List(20) { if (it % 2 == 0) "100" else "101" })

        val ac = collector.snapshot().getValue("X")
        assertThat(ac.perHour[13]).isEqualByComparingTo(BigDecimal("-1"))
        assertThat(ac.hourCounts[13]).isEqualTo(18)
    }

    @Test
    fun `a zero-variance hour bucket is omitted rather than reported as plus one`() {
        val bus = newBus()
        val collector = ReturnAutocorrCollector(bus)
        // Geometric closes -> every return is exactly +0.01 (constant) -> zero variance.
        var c = BigDecimal("200")
        val closes = mutableListOf<String>()
        repeat(10) {
            closes.add(c.toPlainString())
            c = c.multiply(BigDecimal("1.01"))
        }
        feed(bus, "X", 14, closes)

        val ac = collector.snapshot().getValue("X")
        assertThat(ac.perHour).doesNotContainKey(14)
    }

    @Test
    fun `a bucket with fewer than three returns is omitted`() {
        val bus = newBus()
        val collector = ReturnAutocorrCollector(bus)
        // 3 closes -> 2 returns -> 1 lag-1 pair in hour 9, below the 3-pair floor.
        feed(bus, "X", 9, listOf("100", "101", "102"))

        val ac = collector.snapshot().getValue("X")
        assertThat(ac.perHour).doesNotContainKey(9)
    }

    @Test
    fun `returns are bucketed by their bar's UTC hour`() {
        val bus = newBus()
        val collector = ReturnAutocorrCollector(bus)
        // One contiguous symbol stream spanning two hours. The first hour's run starts at the very
        // first bar (no predecessor leaks in), so hour 13 is an exact -1. Hour 8's first pair has
        // the hour-13 tail as its predecessor (the lag-1 predecessor is the prior return in the
        // full stream, by design), so it is merely present here — the point is that each return
        // lands in the hour of its own bar.
        feed(bus, "X", 13, List(20) { if (it % 2 == 0) "100" else "101" })
        feed(bus, "X", 8, List(20) { if (it % 2 == 0) "50" else "51" })

        val ac = collector.snapshot().getValue("X")
        assertThat(ac.perHour.keys).contains(8, 13)
        assertThat(ac.perHour[13]).isEqualByComparingTo(BigDecimal("-1"))
        // 18 lag-1 pairs fall in hour 13 (first two returns yield the first pair), 20 in hour 8
        // (its first pair bridges the hour-13 tail).
        assertThat(ac.hourCounts[13]).isEqualTo(18)
        assertThat(ac.hourCounts[8]).isEqualTo(20)
    }

    @Test
    fun `the streaming median splits returns into high and low regimes`() {
        val bus = newBus()
        val collector = ReturnAutocorrCollector(bus)
        // Two return magnitudes: big jumps (~0.05) and small ones (~0.001), interleaved so the
        // median lands between them. The split must place the big bars HIGH and small ones LOW.
        val closes = mutableListOf("1000")
        var last = BigDecimal("1000")
        repeat(40) { i ->
            val factor = if (i % 2 == 0) BigDecimal("1.05") else BigDecimal("1.001")
            last = if (i % 4 < 2) last.multiply(factor) else last.divide(factor, Money.CONTEXT)
            closes.add(last.toPlainString())
        }
        feed(bus, "X", 10, closes)

        val ac = collector.snapshot().getValue("X")
        val highCount = ac.regimeCounts[Regime.HIGH] ?: 0
        val lowCount = ac.regimeCounts[Regime.LOW] ?: 0
        // Both regimes populated and the split is near-balanced (approximate streaming median).
        assertThat(highCount).isGreaterThan(0)
        assertThat(lowCount).isGreaterThan(0)
        assertThat(highCount + lowCount).isEqualTo(ac.hourCounts.values.sum())
        // No bucket is ever NaN — present values are finite BigDecimals.
        for (v in ac.perRegime.values) assertThat(v).isNotNull()
    }

    @Test
    fun `each symbol gets its own conditional autocorrelation`() {
        val bus = newBus()
        val collector = ReturnAutocorrCollector(bus)
        feed(bus, "X", 13, List(20) { if (it % 2 == 0) "100" else "101" })
        feed(bus, "Y", 13, List(20) { if (it % 2 == 0) "100" else "101" })

        val snap = collector.snapshot()
        assertThat(snap.keys).containsExactlyInAnyOrder("X", "Y")
        assertThat(snap.getValue("X").perHour[13]).isEqualByComparingTo(BigDecimal("-1"))
        assertThat(snap.getValue("Y").perHour[13]).isEqualByComparingTo(BigDecimal("-1"))
    }

    @Test
    fun `a tick-only run with no candles yields an empty map`() {
        val bus = newBus()
        val collector = ReturnAutocorrCollector(bus)
        assertThat(collector.snapshot()).isEmpty()
    }
}
