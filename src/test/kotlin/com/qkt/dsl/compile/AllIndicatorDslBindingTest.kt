package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.stdlib.IndicatorInput
import com.qkt.dsl.stdlib.IndicatorRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class AllIndicatorDslBindingTest {
    @TestFactory
    fun `every registered indicator compiles through its DSL binding`(): List<DynamicTest> =
        IndicatorRegistry
            .names()
            .sorted()
            .map { name ->
                DynamicTest.dynamicTest(name) {
                    val result = Dsl.parse(strategyFor(name))
                    assertThat(result).isInstanceOf(ParseResult.Success::class.java)
                    AstCompiler().compile((result as ParseResult.Success).value)
                }
            }

    private fun strategyFor(name: String): String {
        val spec = requireNotNull(IndicatorRegistry.spec(name))
        val series =
            when (spec.inputKind) {
                IndicatorInput.NUMERIC_SERIES ->
                    if (spec.seriesCount == 2) listOf("a.close", "b.close") else listOf("a.close")
                IndicatorInput.CANDLE_SERIES -> listOf("a.candle")
                IndicatorInput.BOOLEAN_SERIES -> listOf("a.close > a.open")
                IndicatorInput.TICK_SERIES -> listOf("a.tick")
            }
        val args = series + constantsFor(name, spec.arity - spec.seriesCount)
        val call = "${name.lowercase()}(${args.joinToString(", ")})"
        return """
            STRATEGY binding_${name.lowercase()} VERSION 1
            SYMBOLS
              a = BACKTEST:A EVERY 1m
              b = BACKTEST:B EVERY 1m
            RULES
              WHEN $call IS NOT NULL THEN FLATTEN
            """.trimIndent()
    }

    private fun constantsFor(
        name: String,
        count: Int,
    ): List<String> =
        when (name) {
            "MACD", "MACD_SIGNAL", "MACD_HIST" -> listOf("2", "3", "2")
            "STOCH_K", "STOCH_D" -> listOf("3", "2")
            "VARIANCE_RATIO" -> listOf("2", "5")
            "SESSION_RANGE_HIGH", "SESSION_RANGE_LOW" -> listOf("0", "0", "1", "0")
            "SESSION_MOMENTUM" -> listOf("0", "1", "2")
            "VWAP_SESSION", "VWAP_SESSION_STDEV" -> listOf("0")
            "IB_DEFENDED_HIGH", "IB_DEFENDED_LOW" -> listOf("0", "60")
            else -> List(count) { "3" }
        }.also { require(it.size == count) { "$name constants do not match registry arity" } }
}
