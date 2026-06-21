package com.qkt.dsl.stdlib

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The editor tooling (the language server) enumerates the indicator, function, and
 * constant tables to offer completions. These tests pin the read-only `names()`
 * accessors and assert they stay consistent with the existing lookup methods, so a
 * rename or removal in a registry surfaces here rather than silently dropping a
 * completion.
 */
class RegistryNamesTest {
    @Test
    fun `indicator names are non-empty and consistent with has and spec`() {
        val names = IndicatorRegistry.names()
        assertThat(names).contains("EMA", "RSI", "ATR", "MACD", "ADX", "BOLLINGER_UPPER", "VWAP")
        names.forEach {
            assertThat(IndicatorRegistry.has(it)).`as`("has(%s)", it).isTrue()
            assertThat(IndicatorRegistry.spec(it)).`as`("spec(%s)", it).isNotNull()
        }
    }

    @Test
    fun `function names cover the registry and agree with has`() {
        val names = FuncRegistry.names()
        assertThat(names).contains("ABS", "SQRT", "LOG", "EXP", "POW", "MIN", "MAX", "MOD", "FLOOR", "CEIL", "ROUND")
        names.forEach { assertThat(FuncRegistry.has(it)).`as`("has(%s)", it).isTrue() }
    }

    @Test
    fun `constant names map to values`() {
        val names = Constants.names()
        assertThat(names).contains("ONE_PERCENT", "TWO_PERCENT", "BPS")
        names.forEach { assertThat(Constants.byName(it)).`as`("byName(%s)", it).isNotNull() }
    }
}
