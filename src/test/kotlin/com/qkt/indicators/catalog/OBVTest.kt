package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.marketdata.Candle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OBVTest {
    private fun candle(
        c: String,
        vol: String,
    ) = Candle("X", Money.of(c), Money.of(c), Money.of(c), Money.of(c), Money.of(vol), 0L, 1L)

    @Test
    fun `accumulates volume by close direction`() {
        // closes 10,11,10,12 ; vols 100,200,150,300 → 0, +200, -150, +300 = 350.
        val obv = OBV()
        obv.update(candle("10", "100"))
        assertThat(obv.value()).isEqualByComparingTo(Money.ZERO)
        obv.update(candle("11", "200"))
        assertThat(obv.value()).isEqualByComparingTo(Money.of("200"))
        obv.update(candle("10", "150"))
        assertThat(obv.value()).isEqualByComparingTo(Money.of("50"))
        obv.update(candle("12", "300"))
        assertThat(obv.value()).isEqualByComparingTo(Money.of("350"))
    }

    @Test
    fun `flat close leaves the running total unchanged`() {
        val obv = OBV()
        obv.update(candle("10", "100"))
        obv.update(candle("10", "500"))
        assertThat(obv.value()).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `ready after the first candle`() {
        val obv = OBV()
        assertThat(obv.isReady).isFalse()
        obv.update(candle("10", "100"))
        assertThat(obv.isReady).isTrue()
        assertThat(obv.warmupBars).isEqualTo(1)
    }
}
