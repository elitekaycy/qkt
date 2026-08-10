package com.qkt.dsl.compile

import com.qkt.dsl.stdlib.FuncRegistry
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

class GeneratedNumericFunctionStrategyTest {
    private data class Case(
        val call: String,
        val expected: String,
    )

    private val cases =
        mapOf(
            "ABS" to Case("abs(-3.5)", "3.5"),
            "CEIL" to Case("ceil(3.2)", "4"),
            "EXP" to Case("exp(0)", "1"),
            "FLOOR" to Case("floor(-1.2)", "-2"),
            "LOG" to Case("log(1)", "0"),
            "MAX" to Case("max(1, 5, 3)", "5"),
            "MIN" to Case("min(1, 5, 3)", "1"),
            "MOD" to Case("mod(-7, 3)", "2"),
            "NORMALIZE" to Case("normalize(3, 1, 5)", "0.5"),
            "POW" to Case("pow(2, 3)", "8"),
            "RANK_OF" to Case("rank_of(5, 3, 1)", "1"),
            "ROUND" to Case("round(2.5)", "2"),
            "ROUND_TO" to Case("round_to(13, 5)", "15"),
            "SOFTMAX" to Case("softmax(7, 7)", "0.5"),
            "SQRT" to Case("sqrt(16)", "4"),
        )

    @TestFactory
    fun `generated qkt strategies make each numeric function affect a decision`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> {
        assertThat(cases.keys).isEqualTo(FuncRegistry.names())
        return cases.map { (name, case) ->
            DynamicTest.dynamicTest(name) {
                val source =
                    """
                    STRATEGY generated_${name.lowercase()} VERSION 1
                    SYMBOLS
                      s = BACKTEST:X EVERY 1m
                    RULES
                      WHEN ${case.call} = ${case.expected} AND POSITION.s = 0
                      THEN BUY s SIZING 0.01
                    """.trimIndent()
                val path = tempDir.resolve("${name.lowercase()}.qkt")
                Files.writeString(path, source)

                val strategy = GeneratedStrategyReplay.compile(path)
                val signals = mutableListOf<Signal>()
                strategy.onCandle(candle(), testStrategyContext(), signals::add)

                assertThat(signals).hasSize(1)
                assertThat(signals.single()).isInstanceOf(Signal.Buy::class.java)
                val buy = signals.single() as Signal.Buy
                assertThat(buy.symbol).isEqualTo("BACKTEST:X")
                assertThat(buy.size).isEqualByComparingTo("0.01")
                GeneratedStrategyReplay.assertTickAndBarParity(path, listOf("10"))
            }
        }
    }

    private fun candle(): Candle =
        Candle(
            symbol = "BACKTEST:X",
            open = BigDecimal("10"),
            high = BigDecimal("11"),
            low = BigDecimal("9"),
            close = BigDecimal("10"),
            volume = BigDecimal.ONE,
            startTime = 0L,
            endTime = 60_000L,
        )
}
