package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class InMemoryMarketSourceTest {
    private val day15 = Instant.parse("2024-01-15T00:00:00Z").toEpochMilli()

    @Test
    fun `seedLive ticks are returned in timestamp order`() {
        val src = InMemoryMarketSource()
        src.seedLive("X", listOf(Tick("X", Money.of("100"), day15 + 1_000L)))
        src.seedLive("X", listOf(Tick("X", Money.of("101"), day15 + 2_000L)))

        val feed = src.liveTicks(listOf("X"))
        val out = generateSequence { feed.next() }.toList()

        assertThat(out.map { it.timestamp }).containsExactly(day15 + 1_000L, day15 + 2_000L)
    }

    @Test
    fun `multi symbol live feed merges by timestamp`() {
        val src = InMemoryMarketSource()
        src.seedLive("A", listOf(Tick("A", Money.of("1"), day15 + 1_000L)))
        src.seedLive("B", listOf(Tick("B", Money.of("2"), day15 + 500L)))

        val feed = src.liveTicks(listOf("A", "B"))
        val symbols = generateSequence { feed.next() }.map { it.symbol }.toList()
        assertThat(symbols).containsExactly("B", "A")
    }

    @Test
    fun `bars returns candles inside range`() {
        val src = InMemoryMarketSource()
        val c1 =
            Candle(
                "X",
                Money.of("100"),
                Money.of("105"),
                Money.of("99"),
                Money.of("104"),
                Money.ZERO,
                day15,
                day15 + 60_000L,
            )
        val c2 =
            Candle(
                "X",
                Money.of("104"),
                Money.of("110"),
                Money.of("103"),
                Money.of("108"),
                Money.ZERO,
                day15 + 60_000L,
                day15 + 120_000L,
            )
        src.seedBars("X", TimeWindow.ONE_MINUTE, listOf(c1, c2))

        val r = TimeRange(Instant.ofEpochMilli(day15), Instant.ofEpochMilli(day15 + 120_000L))
        val out = src.bars("X", TimeWindow.ONE_MINUTE, r).toList()

        assertThat(out).containsExactly(c1, c2)
    }

    @Test
    fun `ticks throws because the fixture does not implement TICKS`() {
        val src = InMemoryMarketSource()
        val r = TimeRange(Instant.ofEpochMilli(day15), Instant.ofEpochMilli(day15 + 60_000L))
        assertThatThrownBy { src.ticks("X", r).toList() }
            .isInstanceOf(UnsupportedDataException::class.java)
    }

    @Test
    fun `supports only seeded symbols`() {
        val src = InMemoryMarketSource()
        src.seedLive("X", emptyList())
        assertThat(src.supports("X")).isTrue()
        assertThat(src.supports("Y")).isFalse()
    }
}
