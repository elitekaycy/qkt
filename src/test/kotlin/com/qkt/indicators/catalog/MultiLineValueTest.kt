package com.qkt.indicators.catalog

import com.qkt.indicators.IndicatorMap
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Multi-line indicators expose their headline line as `value()`: ADX's adx, Stochastic's %K, Keltner's middle band. */
class MultiLineValueTest {
    private val bars =
        listOf(
            "10" to "8",
            "12" to "9",
            "11" to "7",
            "14" to "10",
            "13" to "11",
            "16" to "12",
            "15" to "13",
            "18" to "14",
        ).mapIndexed { i, (h, l) ->
            val high = BigDecimal(h)
            val low = BigDecimal(l)
            val close = high.add(low).divide(BigDecimal(2))
            Candle("X", low, high, low, close, BigDecimal.ONE, i * 60_000L, (i + 1) * 60_000L)
        }

    @Test
    fun `adx value is the adx line once warm and null before`() {
        val adx = ADX(2)
        assertThat(adx.value()).isNull()

        bars.forEach(adx::update)

        assertThat(adx.value()).isNotNull.isEqualByComparingTo(adx.lines()!!.adx)
    }

    @Test
    fun `stochastic value is the K line`() {
        val stochastic = Stochastic(3, 2)
        bars.forEach(stochastic::update)

        assertThat(stochastic.value()).isNotNull.isEqualByComparingTo(stochastic.lines()!!.k)
    }

    @Test
    fun `keltner value is the middle band`() {
        val keltner = KeltnerChannels(3, BigDecimal("1.5"))
        bars.forEach(keltner::update)

        assertThat(keltner.value()).isNotNull.isEqualByComparingTo(keltner.bands()!!.middle)
    }

    @Test
    fun `an indicator map snapshots every symbol it has built`() {
        val map = IndicatorMap { SMA(2) }
        map.get("A").update(BigDecimal.ONE)
        map.get("B")

        val all = map.all()
        map.get("C")

        assertThat(all.keys).containsExactlyInAnyOrder("A", "B")
    }

    @Test
    fun `confirm ratio refuses a tuple of the wrong width`() {
        assertThatThrownBy { ConfirmRatio(period = 3, peerCount = 2).update(listOf(BigDecimal.ONE, BigDecimal.ONE)) }
            .hasMessageContaining("ConfirmRatio expects 3 values (signal + 2 peers), got 2")
    }
}
