package com.qkt.trade.session

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** `next` per series: every bar of every symbol and timeframe, in order, none skipped, none invented. */
class BotSessionSeriesTest : IntentSessionFixture() {
    @Test
    fun `next on two symbols never skips a bar`() {
        val s = session(listOf(2400, 2410, 2420, 2430, 2440), symbols = listOf(gold, silver))

        val goldBars = (1..3).map { s.next(gold)!!.startTime }
        val silverBars = (1..3).map { s.next(silver)!!.startTime }

        assertThat(goldBars).containsExactly(0L, 60_000L, 120_000L)
        assertThat(silverBars).containsExactly(0L, 60_000L, 120_000L)
    }

    @Test
    fun `next on a higher timeframe returns the hub's bar`() {
        val s = session(listOf(2400, 2410, 2420, 2430, 2440), timeframes = listOf("1m", "2m"))

        val bar = s.next(gold, "2m")!!

        assertThat(bar.startTime).isEqualTo(0L)
        assertThat(bar.endTime).isEqualTo(120_000L)
        assertThat(bar.open).isEqualByComparingTo("2400")
        assertThat(bar.high).isEqualByComparingTo("2411")
        assertThat(bar.close).isEqualByComparingTo("2411")
        assertThat(s.next(gold)!!.startTime).isEqualTo(0L)
    }

    @Test
    fun `an undeclared series is refused at once`() {
        val s = session(listOf(2400, 2410), timeframes = listOf("1m"))

        assertThatThrownBy { s.next(gold, "5m") }.hasMessageContaining("not a session series")
        assertThatThrownBy { s.next(silver) }.hasMessageContaining("not a session series")
        assertThat(s.simNowMs()).isEqualTo(0L)
    }

    @Test
    fun `bars of a series never look past the cursor`() {
        val s = session(listOf(2400, 2410, 2420, 2430), timeframes = listOf("1m", "2m"))
        s.next(gold, "2m")

        assertThat(s.bars(gold, 10, "2m").map { it.startTime }).containsExactly(0L)
        assertThat(s.bars(gold, 10).last().endTime).isLessThanOrEqualTo(s.simNowMs())
    }
}
