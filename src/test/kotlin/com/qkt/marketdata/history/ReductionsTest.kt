package com.qkt.marketdata.history

import com.qkt.common.Money
import com.qkt.indicators.catalog.SMA
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReductionsTest {
    private fun tick(
        ts: Long,
        price: String,
    ) = Tick("X", Money.of(price), ts)

    private fun candle(
        o: String,
        h: String,
        l: String,
        c: String,
    ) = Candle("X", Money.of(o), Money.of(h), Money.of(l), Money.of(c), Money.ZERO, 0L, 1L)

    @Test
    fun `maxPrice and minPrice on tick sequence`() {
        val s = sequenceOf(tick(1, "100"), tick(2, "110"), tick(3, "95"))
        assertThat(s.maxPrice()).isEqualByComparingTo(Money.of("110"))
        assertThat(sequenceOf(tick(1, "100"), tick(2, "110"), tick(3, "95")).minPrice())
            .isEqualByComparingTo(Money.of("95"))
    }

    @Test
    fun `firstPrice and lastPrice on tick sequence`() {
        val s = sequenceOf(tick(1, "100"), tick(2, "110"), tick(3, "95"))
        assertThat(s.firstPrice()).isEqualByComparingTo(Money.of("100"))
        assertThat(sequenceOf(tick(1, "100"), tick(2, "110"), tick(3, "95")).lastPrice())
            .isEqualByComparingTo(Money.of("95"))
    }

    @Test
    fun `reductions on empty sequence return null`() {
        val empty: Sequence<Tick> = emptySequence()
        assertThat(empty.maxPrice()).isNull()
        assertThat(emptySequence<Tick>().minPrice()).isNull()
        assertThat(emptySequence<Tick>().firstPrice()).isNull()
        assertThat(emptySequence<Tick>().lastPrice()).isNull()
    }

    @Test
    fun `highestHigh and lowestLow on candle sequence`() {
        val s = sequenceOf(candle("100", "105", "98", "102"), candle("102", "108", "101", "107"))
        assertThat(s.highestHigh()).isEqualByComparingTo(Money.of("108"))
        assertThat(sequenceOf(candle("100", "105", "98", "102"), candle("102", "108", "101", "107")).lowestLow())
            .isEqualByComparingTo(Money.of("98"))
    }

    @Test
    fun `firstOpen and lastClose on candle sequence`() {
        val s = sequenceOf(candle("100", "105", "98", "102"), candle("102", "108", "101", "107"))
        assertThat(s.firstOpen()).isEqualByComparingTo(Money.of("100"))
        assertThat(sequenceOf(candle("100", "105", "98", "102"), candle("102", "108", "101", "107")).lastClose())
            .isEqualByComparingTo(Money.of("107"))
    }

    @Test
    fun `runThrough feeds prices into Indicator and returns it`() {
        val sma = sequenceOf(Money.of("10"), Money.of("20"), Money.of("30")).runThrough(SMA(3))
        assertThat(sma.isReady).isTrue()
        assertThat(sma.value()).isEqualByComparingTo(Money.of("20"))
    }
}
