package com.qkt.parity

import com.qkt.candles.TimeWindow
import com.qkt.cli.Config
import com.qkt.dsl.compile.GeneratedStrategyReplay
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

class GeneratedMultiSymbolRiskParityTest {
    private data class Case(
        val id: String,
        val maxOpenPositions: Int,
        val expectedTradeSymbols: List<String>,
        val expectedRejectionCount: Int,
    )

    private val cases =
        listOf(
            Case(
                id = "open_position_at_boundary",
                maxOpenPositions = 2,
                expectedTradeSymbols = listOf(X, Y),
                expectedRejectionCount = 0,
            ),
            Case(
                id = "open_position_over_cap",
                maxOpenPositions = 1,
                expectedTradeSymbols = listOf(X),
                expectedRejectionCount = 1,
            ),
        )

    @TestFactory
    fun `loaded open position caps match ticks bars backtest and live paper`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> =
        cases.map { case ->
            DynamicTest.dynamicTest(case.id) {
                val source = strategySource(case.id)
                val strategyPath = tempDir.resolve("${case.id}.qkt")
                Files.writeString(strategyPath, source)
                val configPath = tempDir.resolve("${case.id}.yaml")
                Files.writeString(configPath, riskConfig(case))
                val limits =
                    Config
                        .load(configPath)
                        .perStrategyRisk
                        .getValue(case.id)
                        .toLimits()

                GeneratedStrategyReplay.assertTickAndBarParity(
                    path = strategyPath,
                    candlesBySymbol = CANDLES,
                    window = TimeWindow.ONE_MINUTE,
                    closeOnlyTicks = true,
                    expectedTradeCount = case.expectedTradeSymbols.size,
                    expectedRejectionCount = case.expectedRejectionCount,
                    strategyRiskLimits = limits,
                )

                val result =
                    DslParityHarness.run(
                        strategyId = case.id,
                        source = source,
                        ticks = TICKS,
                        strategyRiskLimits = limits,
                    )

                assertThat(result.live).isEqualTo(result.backtest)
                assertThat(result.backtest.trades.map { it.symbol })
                    .containsExactlyElementsOf(case.expectedTradeSymbols)
                assertThat(result.backtest.rejections).hasSize(case.expectedRejectionCount)
                if (case.expectedRejectionCount > 0) {
                    assertThat(
                        result.backtest.rejections
                            .single()
                            .reason,
                    ).contains("MaxStrategyOpenPositions", "max 1")
                }
            }
        }

    private fun strategySource(id: String): String =
        """
        STRATEGY $id VERSION 1
        SYMBOLS
          x = $X EVERY 1m
          y = $Y EVERY 1m
        RULES
          WHEN x.close = 100 AND POSITION.x = 0
          THEN BUY x SIZING 1

          WHEN y.close = 200 AND POSITION.y = 0
          THEN BUY y SIZING 1
        """.trimIndent()

    private fun riskConfig(case: Case): String =
        """
        risk:
          per_strategy:
            ${case.id}:
              max_open_positions: ${case.maxOpenPositions}
        """.trimIndent()

    private companion object {
        const val X = "BACKTEST:X"
        const val Y = "BACKTEST:Y"
        val CANDLES: Map<String, List<Candle>> =
            mapOf(
                X to candles(X, listOf("100", "100", "101", "101")),
                Y to candles(Y, listOf("199", "200", "201", "201")),
            )
        val TICKS: List<Tick> =
            CANDLES.values
                .flatten()
                .map { candle -> Tick(candle.symbol, candle.close, candle.startTime) }
                .sortedWith(compareBy<Tick> { it.timestamp }.thenBy { it.symbol })

        fun candles(
            symbol: String,
            closes: List<String>,
        ): List<Candle> =
            closes.mapIndexed { index, close ->
                val price = BigDecimal(close)
                Candle(
                    symbol = symbol,
                    open = price,
                    high = price,
                    low = price,
                    close = price,
                    volume = BigDecimal.ONE,
                    startTime = index * 60_000L,
                    endTime = (index + 1) * 60_000L,
                )
            }
    }
}
