package com.qkt.research

import com.qkt.common.Money
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BarResolvedFeedTest {
    // Candle(symbol, open, high, low, close, volume, startTime, endTime)
    private fun bar(start: Long, o: String, h: String, l: String, c: String) =
        Candle("X", Money.of(o), Money.of(h), Money.of(l), Money.of(c), Money.of("0"), start, start + 1000)

    private fun drain(f: BarResolvedFeed): List<java.math.BigDecimal> {
        val out = mutableListOf<java.math.BigDecimal>()
        while (true) {
            val t = f.next() ?: break
            out.add(t.price)
        }
        return out
    }

    @Test
    fun `fill-possible bar streams the real slice`() {
        val realTicks = listOf(Tick("X", Money.of("100"), 0), Tick("X", Money.of("101"), 500))
        val f =
            BarResolvedFeed(
                bars = sequenceOf(bar(0, "100", "102", "99", "101")),
                sliceProvider = { _, _, _ -> realTicks },
                fillPossible = { _, _, _ -> true },
            )
        assertThat(drain(f)).containsExactly(Money.of("100"), Money.of("101")) // the real ticks, not O-L-H-C
    }

    @Test
    fun `fill-impossible bar streams synthetic O-L-H-C`() {
        val f =
            BarResolvedFeed(
                bars = sequenceOf(bar(0, "100", "102", "99", "101")),
                sliceProvider = { _, _, _ -> error("must not slice a fill-impossible bar") },
                fillPossible = { _, _, _ -> false },
            )
        // open, low, high, close
        assertThat(drain(f)).containsExactly(Money.of("100"), Money.of("99"), Money.of("102"), Money.of("101"))
    }

    @Test
    fun `predicate is queried per bar with that bar's range`() {
        val seen = mutableListOf<Pair<java.math.BigDecimal, java.math.BigDecimal>>()
        val f =
            BarResolvedFeed(
                bars = sequenceOf(bar(0, "100", "102", "99", "101"), bar(1000, "101", "105", "100", "104")),
                sliceProvider = { _, _, _ -> listOf(Tick("X", Money.of("1"), 0)) },
                fillPossible = { _, low, high -> seen.add(low to high); false },
            )
        drain(f)
        assertThat(seen).containsExactly(Money.of("99") to Money.of("102"), Money.of("100") to Money.of("105"))
    }
}
