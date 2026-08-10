package com.qkt.parity

import com.qkt.cli.Config
import com.qkt.common.Money
import com.qkt.dsl.compile.GeneratedStrategyReplay
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

class GeneratedSizingRiskParityTest {
    private data class SizingCase(
        val id: String,
        val action: String,
        val prices: List<String> = listOf("100", "100"),
        val expectedQuantities: List<String>,
        val bookCapital: BigDecimal? = null,
    )

    private data class RiskCase(
        val id: String,
        val maxOrderQty: String,
        val maxOrderNotional: String,
        val maxPositionSize: String,
        val expectedRejection: String?,
    )

    private val sizingCases =
        listOf(
            SizingCase("size_qty", "BUY x SIZING 2", expectedQuantities = listOf("2")),
            SizingCase("size_notional", "BUY x SIZING 500 USD", expectedQuantities = listOf("5")),
            SizingCase("size_pct_equity", "BUY x SIZING 5 PCT OF EQUITY", expectedQuantities = listOf("5")),
            SizingCase("size_pct_balance", "BUY x SIZING 5 PCT OF BALANCE", expectedQuantities = listOf("5")),
            SizingCase(
                "size_risk_abs",
                "BUY x SIZING RISK \$ 50 BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 50 }",
                expectedQuantities = listOf("10"),
            ),
            SizingCase(
                "size_risk_frac",
                "BUY x SIZING 1 PCT RISK BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 50 }",
                expectedQuantities = listOf("20"),
            ),
            SizingCase(
                "size_risk_book",
                "BUY x SIZING 1 PCT RISK OF BOOK BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 50 }",
                expectedQuantities = listOf("40"),
                bookCapital = BigDecimal("20000"),
            ),
            SizingCase(
                id = "size_position_full",
                action =
                    """
                    BUY x SIZING 2

                      WHEN x.close = 110 AND POSITION.x != 0
                      THEN SELL x SIZING POSITION.x
                    """.trimIndent(),
                prices = listOf("100", "100", "110", "110"),
                expectedQuantities = listOf("2", "2"),
            ),
        )

    private val riskCases =
        listOf(
            RiskCase("risk_at_boundary", "5", "505", "5", expectedRejection = null),
            RiskCase("risk_global_qty", "4.999", "1000", "10", expectedRejection = "per-order cap"),
            RiskCase("risk_global_notional", "10", "504.999", "10", expectedRejection = "order notional"),
            RiskCase("risk_strategy_position", "10", "1000", "4.999", expectedRejection = "MaxStrategyPositionSize"),
        )

    @TestFactory
    fun `generated sizing forms match ticks bars backtest and live paper`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> =
        sizingCases.map { case ->
            DynamicTest.dynamicTest(case.id) {
                val source = strategySource(case.id, case.action)
                val path = tempDir.resolve("${case.id}.qkt")
                Files.writeString(path, source)

                GeneratedStrategyReplay.assertTickAndBarParity(
                    path = path,
                    closes = case.prices,
                    expectedTradeCount = case.expectedQuantities.size,
                    startingBalance = STARTING_BALANCE,
                    bookCapital = case.bookCapital,
                    instruments = unitInstrument,
                )

                val result =
                    DslParityHarness.run(
                        strategyId = case.id,
                        source = source,
                        ticks = ticks(case.prices + case.prices.last()),
                        startingBalance = STARTING_BALANCE,
                        instruments = unitInstrument,
                        bookCapital = case.bookCapital,
                    )

                assertThat(result.live).isEqualTo(result.backtest)
                assertThat(result.backtest.rejections).isEmpty()
                assertThat(result.backtest.trades.map { it.quantity })
                    .containsExactlyElementsOf(case.expectedQuantities)
            }
        }

    @TestFactory
    fun `loaded risk config boundaries match backtest and live paper`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> =
        riskCases.map { case ->
            DynamicTest.dynamicTest(case.id) {
                val source = strategySource(case.id, "BUY x SIZING 5")
                val strategyPath = tempDir.resolve("${case.id}.qkt")
                Files.writeString(strategyPath, source)
                val configPath = tempDir.resolve("${case.id}.yaml")
                Files.writeString(configPath, riskConfig(case))
                val config = Config.load(configPath)
                val strategyRisk = config.perStrategyRisk.getValue(case.id).toLimits()

                GeneratedStrategyReplay.assertTickAndBarParity(
                    path = strategyPath,
                    closes = listOf("99", "100", "101"),
                    expectedTradeCount = if (case.expectedRejection == null) 1 else 0,
                    expectedRejectionCount = if (case.expectedRejection == null) 0 else 1,
                    startingBalance = STARTING_BALANCE,
                    instruments = unitInstrument,
                    strategyRiskLimits = strategyRisk,
                    maxOrderQty = config.maxOrderQty,
                    maxOrderNotional = config.maxOrderNotional,
                )

                val result =
                    DslParityHarness.run(
                        strategyId = case.id,
                        source = source,
                        ticks = ticks(listOf("99", "100", "101", "101")),
                        startingBalance = STARTING_BALANCE,
                        instruments = unitInstrument,
                        strategyRiskLimits = strategyRisk,
                        maxOrderQty = config.maxOrderQty,
                        maxOrderNotional = config.maxOrderNotional,
                    )

                assertThat(result.live).isEqualTo(result.backtest)
                if (case.expectedRejection == null) {
                    assertThat(result.backtest.rejections).isEmpty()
                    assertThat(result.backtest.trades).hasSize(1)
                } else {
                    assertThat(result.backtest.trades).isEmpty()
                    assertThat(result.backtest.rejections)
                        .hasSize(1)
                        .allSatisfy { rejection ->
                            assertThat(rejection.reason).contains(case.expectedRejection)
                        }
                }
            }
        }

    private fun strategySource(
        id: String,
        action: String,
    ): String =
        """
        STRATEGY $id VERSION 1
        SYMBOLS x = BACKTEST:X EVERY 1m
        RULES
          WHEN x.close = 100 AND POSITION.x = 0
          THEN ${action.prependIndent("  ").trimStart()}
        """.trimIndent()

    private fun riskConfig(case: RiskCase): String =
        """
        risk:
          max_order_qty: "${case.maxOrderQty}"
          max_order_notional: "${case.maxOrderNotional}"
          per_strategy:
            ${case.id}:
              max_position_size: "${case.maxPositionSize}"
        """.trimIndent()

    private fun ticks(prices: List<String>): List<Tick> =
        prices.mapIndexed { index, price ->
            Tick(SYMBOL, Money.of(price), index * 60_000L)
        }

    private companion object {
        const val SYMBOL = "BACKTEST:X"
        val STARTING_BALANCE: BigDecimal = BigDecimal("10000")
        val unitInstrument =
            object : InstrumentRegistry {
                override fun lookup(qktSymbol: String): InstrumentMeta =
                    InstrumentMeta(
                        qktSymbol = qktSymbol,
                        contractSize = BigDecimal.ONE,
                        volumeStep = BigDecimal("0.001"),
                        volumeMin = BigDecimal("0.001"),
                        volumeMax = null,
                        pointSize = BigDecimal("0.01"),
                        digits = 2,
                        tradeStopsLevelPoints = 0,
                    )
            }
    }
}
