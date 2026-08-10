package com.qkt.parity

import com.qkt.cli.Config
import com.qkt.dsl.compile.GeneratedStrategyReplay
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

class GeneratedBookLimitParityTest {
    private data class Case(
        val id: String,
        val limits: String,
        val expectedReason: String?,
    )

    private val cases =
        listOf(
            Case(
                id = "book_limits_at_boundary",
                limits =
                    """
                    max_gross_exposure: "1.01"
                    max_net_exposure: "1.01"
                    max_symbol_concentration: "1.01"
                    """.trimIndent(),
                expectedReason = null,
            ),
            Case("book_gross_over_cap", "max_gross_exposure: \"1.009\"", "book gross exposure"),
            Case("book_net_over_cap", "max_net_exposure: \"1.009\"", "book net exposure"),
            Case(
                "book_concentration_over_cap",
                "max_symbol_concentration: \"1.009\"",
                "concentration",
            ),
        )

    @TestFactory
    fun `loaded book limits match ticks bars backtest and live paper`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> =
        cases.map { case ->
            DynamicTest.dynamicTest(case.id) {
                val source = strategySource(case.id)
                val strategyPath = tempDir.resolve("${case.id}.qkt")
                Files.writeString(strategyPath, source)
                val configPath = tempDir.resolve("${case.id}.yaml")
                Files.writeString(configPath, bookRiskConfig(case))
                val config = Config.load(configPath)
                val expectedRejections = if (case.expectedReason == null) 0 else 1

                val result =
                    GeneratedStrategyReplay.assertTickBarAndLiveParity(
                        path = strategyPath,
                        closes = listOf("99", "100", "101"),
                        expectedTradeCount = if (case.expectedReason == null) 1 else 0,
                        expectedRejectionCount = expectedRejections,
                        startingBalance = CAPITAL,
                        instruments = unitInstrument,
                        bookRiskConfig = config.bookRisk,
                    )

                assertThat(result.backtest.rejections).hasSize(expectedRejections)
                if (case.expectedReason == null) {
                    assertThat(result.backtest.trades).hasSize(1)
                } else {
                    assertThat(result.backtest.trades).isEmpty()
                    assertThat(
                        result.backtest.rejections
                            .single()
                            .reason,
                    ).contains(case.expectedReason)
                }
            }
        }

    private fun strategySource(id: String): String =
        """
        STRATEGY $id VERSION 1
        SYMBOLS x = BACKTEST:X EVERY 1m
        RULES
          WHEN x.close = 100 AND POSITION.x = 0
          THEN BUY x SIZING 10
        """.trimIndent()

    private fun bookRiskConfig(case: Case): String =
        "book_risk:\n" +
            "  capital: \"$CAPITAL\"\n" +
            "  limits:\n" +
            case.limits.prependIndent("    ")

    private companion object {
        val CAPITAL: BigDecimal = BigDecimal("1000")
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
