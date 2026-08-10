package com.qkt.dsl.compile

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

class GeneratedMovingAverageStrategyTest {
    private data class Case(
        val call: String,
        val expected: String,
        val closes: List<String>,
    )

    private val cases =
        mapOf(
            "EMA" to Case("ema(s.close, 2)", "1.5", listOf("1", "2")),
            "SMA" to Case("sma(s.close, 3)", "2", listOf("1", "2", "3")),
            "WMA" to Case("wma(s.close, 3)", "2.33333333", listOf("1", "2", "3")),
            "DEMA" to Case("dema(s.close, 2)", "5", listOf("1", "2", "3", "4", "5")),
            "TEMA" to Case("tema(s.close, 2)", "4", listOf("1", "2", "3", "4")),
            "HMA" to Case("hma(s.close, 4)", "5.00000001", listOf("1", "2", "3", "4", "5")),
        )

    @TestFactory
    fun `generated qkt strategies make each moving average affect a decision`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> =
        cases.map { (name, case) ->
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
                case.closes.forEachIndexed { index, close ->
                    strategy.onCandle(candle(close, index), testStrategyContext(), signals::add)
                }

                assertThat(signals).hasSize(1)
                assertThat(signals.single()).isInstanceOf(Signal.Buy::class.java)
                assertThat((signals.single() as Signal.Buy).size).isEqualByComparingTo("0.01")
                GeneratedStrategyReplay.assertTickAndBarParity(path, case.closes)
            }
        }

    private fun candle(
        close: String,
        index: Int,
    ): Candle =
        Candle(
            symbol = "BACKTEST:X",
            open = BigDecimal(close),
            high = BigDecimal(close),
            low = BigDecimal(close),
            close = BigDecimal(close),
            volume = BigDecimal.ONE,
            startTime = index * 60_000L,
            endTime = (index + 1) * 60_000L,
        )
}
