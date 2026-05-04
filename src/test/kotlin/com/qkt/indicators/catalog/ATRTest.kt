package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.marketdata.Candle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ATRTest {
    private fun candle(
        h: String,
        l: String,
        c: String,
        ts: Long = 0L,
    ) = Candle(
        symbol = "X",
        open = Money.of(c),
        high = Money.of(h),
        low = Money.of(l),
        close = Money.of(c),
        volume = Money.of("1"),
        startTime = ts,
        endTime = ts + 1,
    )

    @Test
    fun `not ready before N plus 1 candles`() {
        val atr = ATR(2)
        atr.update(candle("12", "10", "11"))
        atr.update(candle("15", "11", "14"))
        assertThat(atr.isReady).isFalse()
        assertThat(atr.value()).isNull()
        assertThat(atr.warmupBars).isEqualTo(3)
    }

    @Test
    fun `Wilder smoothing on a known sequence`() {
        val atr = ATR(2)
        atr.update(candle("12", "10", "11"))
        atr.update(candle("15", "11", "14")) // TR = 4
        atr.update(candle("14", "10", "12")) // TR = 4 -> seed ATR = 4
        assertThat(atr.value()).isEqualByComparingTo(Money.of("4"))
        atr.update(candle("18", "13", "17")) // TR = 6 -> ATR = (4*1 + 6)/2 = 5
        assertThat(atr.value()).isEqualByComparingTo(Money.of("5"))
    }

    @Test
    fun `flat candles yield ATR zero`() {
        val atr = ATR(2)
        repeat(5) { atr.update(candle("10", "10", "10", ts = it.toLong())) }
        assertThat(atr.value()).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `rejects non-positive period`() {
        assertThatThrownBy { ATR(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ATR(-1) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
