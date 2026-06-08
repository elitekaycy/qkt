package com.qkt.dsl.stdlib

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards the candle-fed indicator wiring (#320). The multi-output STOCH wrappers must be typed
 * `Indicator<Candle>` (not `Indicator<BigDecimal>` like MACD's), or the CANDLE_SERIES update path
 * in IndicatorBinding would ClassCastException at runtime. Creating each via the registry and
 * feeding it a candle exercises exactly that cast.
 */
class CandleIndicatorRegistryTest {
    private fun candle(
        h: String,
        l: String,
        c: String,
        vol: String = "1",
    ) = Candle("X", Money.of(c), Money.of(h), Money.of(l), Money.of(c), Money.of(vol), 0L, 1L)

    @Suppress("UNCHECKED_CAST")
    private fun create(
        name: String,
        consts: List<Int>,
    ): Indicator<Candle> = IndicatorRegistry.create(name, consts.map { BigDecimal(it) }) as Indicator<Candle>

    @Test
    fun `single-output candle oscillators consume candles and report a value`() {
        for ((name, consts) in listOf("WILLIAMS_R" to listOf(3), "CCI" to listOf(3))) {
            val ind = create(name, consts)
            repeat(3) { ind.update(candle("11", "9", "10")) }
            assertThat(ind.value()).withFailMessage("$name should report a value").isNotNull()
        }
    }

    @Test
    fun `multi-output stochastic wrappers feed the shared instance via the candle cast`() {
        for (name in listOf("STOCH_K", "STOCH_D")) {
            val ind = create(name, listOf(3, 1))
            repeat(3) { ind.update(candle("11", "9", "10")) }
            // HH=11, LL=9, close=10 → %K=50, %D=SMA(%K,1)=50.
            assertThat(ind.value()).isEqualByComparingTo(Money.of("50"))
        }
    }

    @Test
    fun `obv accumulates via the candle path`() {
        val obv = create("OBV", emptyList())
        obv.update(candle("10", "10", "10", vol = "100"))
        obv.update(candle("11", "11", "11", vol = "200"))
        assertThat(obv.value()).isEqualByComparingTo(Money.of("200"))
    }

    @Test
    fun `keltner and adx multi-output wrappers feed candles and report values`() {
        for (name in listOf("KELTNER_UPPER", "KELTNER_MIDDLE", "KELTNER_LOWER")) {
            val ind = create(name, listOf(5, 2))
            repeat(12) { ind.update(candle("105", "95", "100")) }
            assertThat(ind.value()).withFailMessage("$name should report a value").isNotNull()
        }
        for (name in listOf("PLUS_DI", "MINUS_DI", "ADX")) {
            val ind = create(name, listOf(14))
            repeat(40) { i -> ind.update(candle("${100 + i}", "${99 + i}", "${100 + i}")) }
            assertThat(ind.value()).withFailMessage("$name should report a value").isNotNull()
        }
    }
}
