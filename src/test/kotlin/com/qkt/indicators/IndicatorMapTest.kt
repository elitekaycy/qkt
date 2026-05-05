package com.qkt.indicators

import com.qkt.common.Money
import com.qkt.indicators.catalog.SMA
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IndicatorMapTest {
    @Test
    fun `get creates indicator on first call via factory`() {
        val map = IndicatorMap { SMA(3) }
        val sma = map.get("EURUSD")
        assertThat(sma).isInstanceOf(SMA::class.java)
        assertThat(sma.isReady).isFalse()
    }

    @Test
    fun `get returns same instance on subsequent calls`() {
        val map = IndicatorMap { SMA(3) }
        val a = map.get("EURUSD")
        val b = map.get("EURUSD")
        assertThat(a).isSameAs(b)
    }

    @Test
    fun `has and symbols reflect state`() {
        val map = IndicatorMap { SMA(3) }
        assertThat(map.has("EURUSD")).isFalse()
        map.get("EURUSD")
        map.get("XAUUSD")
        assertThat(map.has("EURUSD")).isTrue()
        assertThat(map.symbols()).containsExactlyInAnyOrder("EURUSD", "XAUUSD")
    }

    @Test
    fun `independent state per symbol`() {
        val map = IndicatorMap { SMA(2) }
        map.get("A").update(Money.of("10"))
        map.get("A").update(Money.of("20"))
        map.get("B").update(Money.of("100"))
        assertThat(map.get("A").value()).isEqualByComparingTo(Money.of("15"))
        assertThat(map.get("B").value()).isNull()
    }
}
