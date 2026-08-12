package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.stdlib.FuncRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class AllNumericFunctionDslBindingTest {
    private val calls =
        mapOf(
            "ABS" to "abs(-3.5)",
            "CEIL" to "ceil(3.2)",
            "EXP" to "exp(1)",
            "FLOOR" to "floor(3.8)",
            "LOG" to "log(2)",
            "MAX" to "max(1, 2, 3)",
            "MIN" to "min(1, 2, 3)",
            "MOD" to "mod(7, 3)",
            "NORMALIZE" to "normalize(3, 1, 5)",
            "POW" to "pow(2, 3)",
            "RANK_OF" to "rank_of(3, 1, 5)",
            "ROUND" to "round(2.5)",
            "ROUND_TO" to "round_to(13, 5)",
            "SOFTMAX" to "softmax(3, 1, 5)",
            "SQRT" to "sqrt(16)",
        )

    @TestFactory
    fun `every registered numeric function compiles through its DSL binding`(): List<DynamicTest> {
        assertThat(calls.keys).isEqualTo(FuncRegistry.names())
        return calls.map { (name, call) ->
            DynamicTest.dynamicTest(name) {
                val source =
                    """
                    STRATEGY binding_${name.lowercase()} VERSION 1
                    SYMBOLS
                      s = BACKTEST:X EVERY 1m
                    RULES
                      WHEN $call IS NOT NULL THEN FLATTEN
                    """.trimIndent()
                val result = Dsl.parse(source)
                assertThat(result).isInstanceOf(ParseResult.Success::class.java)
                AstCompiler().compile((result as ParseResult.Success).value)
            }
        }
    }
}
