package com.qkt.parity

import com.qkt.candles.TimeWindow
import com.qkt.cli.Config
import com.qkt.cli.liveRiskLimits
import com.qkt.dsl.compile.GeneratedStrategyReplay
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

/**
 * Symbol-set caps bind a live paper session exactly as they bind a backtest (#1713): every member of the set signals
 * on the same bar, so live risk checks the whole burst before any fill returns, and the in-flight members must count.
 */
class GeneratedSetCapParityTest {
    private data class Case(
        val id: String,
        val caps: String,
        val expectedTradeSymbols: List<String>,
        val rejection: String?,
        val rejections: Int,
    )

    private val cases =
        listOf(
            Case("set_open_at_boundary", "max_open_positions: 3", listOf(X, Y, Z), null, 0),
            Case("set_open_burst_over_cap", "max_open_positions: 2", listOf(X, Y), "MaxSetOpenPositions", 1),
            Case("set_size_over_cap", "max_position_size: \"0.5\"", emptyList(), "MaxSetPositionSize", 3),
        )

    @TestFactory
    fun `set caps match ticks, bars and live paper on a burst of every member`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> =
        cases.map { case ->
            DynamicTest.dynamicTest(case.id) {
                val strategyPath = tempDir.resolve("${case.id}.qkt")
                Files.writeString(strategyPath, strategySource(case.id))
                val configPath = tempDir.resolve("${case.id}.yaml")
                Files.writeString(configPath, riskConfig(case))
                val ast = (Dsl.parseFile(strategyPath) as ParseResult.Success).value
                val risk = Config.load(configPath).perStrategyRisk.getValue(case.id)

                val result =
                    GeneratedStrategyReplay.assertTickBarAndLiveParity(
                        path = strategyPath,
                        candlesBySymbol = CANDLES,
                        window = TimeWindow.ONE_MINUTE,
                        closeOnlyTicks = true,
                        expectedTradeCount = case.expectedTradeSymbols.size,
                        expectedRejectionCount = case.rejections,
                        strategyRiskLimits = liveRiskLimits(case.id, risk, ast.streams)!!,
                    )

                assertThat(result.backtest.trades.map { it.symbol })
                    .containsExactlyInAnyOrderElementsOf(case.expectedTradeSymbols)
                assertThat(result.backtest.rejections).hasSize(case.rejections)
                case.rejection?.let { rule ->
                    assertThat(result.backtest.rejections).allSatisfy { assertThat(it.reason).contains(rule, "/uni]") }
                }
            }
        }

    private fun strategySource(id: String): String =
        """
        STRATEGY $id VERSION 1
        SYMBOLS
          uni = BACKTEST:{X, Y, Z} EVERY 1m
        RULES
          FOR EACH m IN uni DO WHEN m.close = 100 THEN BUY m SIZING 1
        """.trimIndent()

    private fun riskConfig(case: Case): String =
        """
        risk:
          per_strategy:
            ${case.id}:
              sets:
                uni: { ${case.caps} }
        """.trimIndent()

    private companion object {
        const val X = "BACKTEST:X"
        const val Y = "BACKTEST:Y"
        const val Z = "BACKTEST:Z"
        private val CLOSES = listOf("99", "100", "101", "101")
        val CANDLES: Map<String, List<Candle>> = listOf(X, Y, Z).associateWith { candles(it) }

        fun candles(symbol: String): List<Candle> =
            CLOSES.mapIndexed { index, close ->
                val price = BigDecimal(close)
                Candle(symbol, price, price, price, price, BigDecimal.ONE, index * 60_000L, (index + 1) * 60_000L)
            }
    }
}
