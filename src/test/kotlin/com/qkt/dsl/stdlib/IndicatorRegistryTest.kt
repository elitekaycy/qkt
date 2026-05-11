package com.qkt.dsl.stdlib

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class IndicatorRegistryTest {
    @Test
    fun `EMA RSI ATR are registered`() {
        assertThat(IndicatorRegistry.has("EMA")).isTrue()
        assertThat(IndicatorRegistry.has("RSI")).isTrue()
        assertThat(IndicatorRegistry.has("ATR")).isTrue()
    }

    @Test
    fun `EMA spec wants a numeric series and one period arg`() {
        val spec = IndicatorRegistry.spec("EMA")!!
        assertThat(spec.inputKind).isEqualTo(IndicatorInput.NUMERIC_SERIES)
        assertThat(spec.arity).isEqualTo(2)
    }

    @Test
    fun `ATR spec wants a candle series and one period arg`() {
        val spec = IndicatorRegistry.spec("ATR")!!
        assertThat(spec.inputKind).isEqualTo(IndicatorInput.CANDLE_SERIES)
        assertThat(spec.arity).isEqualTo(2)
    }

    @Test
    fun `creating EMA returns an EMA indicator`() {
        val ind = IndicatorRegistry.create("EMA", listOf(java.math.BigDecimal("9")))
        assertThat(ind).isInstanceOf(com.qkt.indicators.catalog.EMA::class.java)
    }

    @Test
    fun `creating with the wrong arity throws`() {
        assertThatThrownBy { IndicatorRegistry.create("EMA", emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `unknown indicator returns null spec`() {
        assertThat(IndicatorRegistry.spec("UNKNOWN")).isNull()
    }

    @Test
    fun `phase 23 catalog expansion is registered`() {
        val expected =
            listOf(
                "SMA",
                "WMA",
                "MACD",
                "MACD_SIGNAL",
                "MACD_HIST",
                "BOLLINGER_UPPER",
                "BOLLINGER_MIDDLE",
                "BOLLINGER_LOWER",
                "HIGHEST",
                "LOWEST",
            )
        for (name in expected) {
            assertThat(IndicatorRegistry.has(name))
                .withFailMessage("indicator %s missing from registry", name)
                .isTrue()
        }
    }

    @Test
    fun `SMA creates an SMA instance`() {
        val ind = IndicatorRegistry.create("SMA", listOf(java.math.BigDecimal("20")))
        assertThat(ind).isInstanceOf(com.qkt.indicators.catalog.SMA::class.java)
    }

    @Test
    fun `WMA creates a WMA instance`() {
        val ind = IndicatorRegistry.create("WMA", listOf(java.math.BigDecimal("10")))
        assertThat(ind).isInstanceOf(com.qkt.indicators.catalog.WMA::class.java)
    }

    @Test
    fun `MACD has arity 4 numeric series`() {
        val spec = IndicatorRegistry.spec("MACD")!!
        assertThat(spec.arity).isEqualTo(4)
        assertThat(spec.inputKind).isEqualTo(IndicatorInput.NUMERIC_SERIES)
    }

    @Test
    fun `Bollinger has arity 3 numeric series`() {
        val spec = IndicatorRegistry.spec("BOLLINGER_UPPER")!!
        assertThat(spec.arity).isEqualTo(3)
        assertThat(spec.inputKind).isEqualTo(IndicatorInput.NUMERIC_SERIES)
    }

    @Test
    fun `HIGHEST creates a RollingHigh instance`() {
        val ind = IndicatorRegistry.create("HIGHEST", listOf(java.math.BigDecimal("20")))
        assertThat(ind).isInstanceOf(com.qkt.indicators.catalog.RollingHigh::class.java)
    }

    @Test
    fun `LOWEST creates a RollingLow instance`() {
        val ind = IndicatorRegistry.create("LOWEST", listOf(java.math.BigDecimal("10")))
        assertThat(ind).isInstanceOf(com.qkt.indicators.catalog.RollingLow::class.java)
    }
}
