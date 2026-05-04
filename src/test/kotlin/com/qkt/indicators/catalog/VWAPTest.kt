package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class VWAPTest {
    private fun tick(
        price: String,
        volume: String?,
        ts: Long = 0L,
    ) = Tick(
        symbol = "X",
        price = Money.of(price),
        timestamp = ts,
        volume = volume?.let { Money.of(it) },
    )

    @Test
    fun `not ready before warmup`() {
        val vwap = VWAP(2)
        vwap.update(tick("10", "1"))
        assertThat(vwap.isReady).isFalse()
        assertThat(vwap.value()).isNull()
        assertThat(vwap.warmupBars).isEqualTo(2)
    }

    @Test
    fun `volume weighted mean over the rolling window`() {
        val vwap = VWAP(2)
        vwap.update(tick("10", "2"))
        vwap.update(tick("20", "3"))
        assertThat(vwap.isReady).isTrue()
        assertThat(vwap.value()).isEqualByComparingTo(Money.of("16"))
    }

    @Test
    fun `equals arithmetic mean when all volumes equal`() {
        val vwap = VWAP(2)
        vwap.update(tick("10", "1"))
        vwap.update(tick("20", "1"))
        assertThat(vwap.value()).isEqualByComparingTo(Money.of("15"))
    }

    @Test
    fun `throws on null volume`() {
        val vwap = VWAP(2)
        assertThatThrownBy { vwap.update(tick("10", volume = null)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("volume")
    }

    @Test
    fun `rejects non-positive period`() {
        assertThatThrownBy { VWAP(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { VWAP(-1) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
