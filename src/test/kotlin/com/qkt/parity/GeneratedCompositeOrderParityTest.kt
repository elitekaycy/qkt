package com.qkt.parity

import com.qkt.dsl.compile.GeneratedStrategyReplay
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

class GeneratedCompositeOrderParityTest {
    private data class Case(
        val id: String,
        val action: String,
        val prices: List<String>,
        val expectedSides: List<String>,
    )

    private val cases =
        listOf(
            Case(
                id = "bracket",
                action = "BUY x SIZING 0.01 BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 10 }",
                prices = listOf("100", "100", "111", "111"),
                expectedSides = listOf("BUY", "SELL"),
            ),
            Case(
                id = "standalone_oco",
                action =
                    """
                    OCO_ENTRY {
                      BUY x SIZING 0.01 ORDER_TYPE = STOP AT 105,
                      SELL x SIZING 0.01 ORDER_TYPE = STOP AT 95
                    }
                    """.trimIndent(),
                prices = listOf("100", "100", "106", "106"),
                expectedSides = listOf("BUY"),
            ),
            Case(
                id = "oto",
                action =
                    """
                    BUY x SIZING 0.01
                      ON_FILL { SELL x SIZING 0.01 ORDER_TYPE = LIMIT AT entry + 5 }
                    """.trimIndent(),
                prices = listOf("100", "100", "106", "106"),
                expectedSides = listOf("BUY", "SELL"),
            ),
            Case(
                id = "stack",
                action = "BUY x SIZING 0.01 STACK 3 SPACING 5 ABOVE WITHIN 30m",
                prices = listOf("100", "100", "105", "110", "110"),
                expectedSides = listOf("BUY", "BUY", "BUY"),
            ),
            Case(
                id = "armed_trailing_stop",
                action =
                    """
                    BUY x SIZING 0.01
                      BRACKET { STOP LOSS TRAILING 5 AFTER MFE >= 10, TAKE PROFIT BY 50 }
                    """.trimIndent(),
                prices = listOf("100", "102", "108", "112", "108", "106", "106"),
                expectedSides = listOf("BUY", "SELL"),
            ),
            Case(
                id = "stepped_stop",
                action =
                    """
                    BUY x SIZING 0.01 BRACKET {
                      STOP LOSS BY 5
                        STEP TO BREAKEVEN AFTER MFE >= 10
                        STEP TO ENTRY + 5 AFTER MFE >= 15,
                      TAKE PROFIT BY 50
                    }
                    """.trimIndent(),
                prices = listOf("100", "102", "112", "117", "111", "106", "106"),
                expectedSides = listOf("BUY", "SELL"),
            ),
            Case(
                id = "time_tightening_stop",
                action =
                    """
                    BUY x SIZING 0.01 BRACKET {
                      STOP LOSS BY 60 TIGHTEN BY 10 EVERY 1m FLOOR 20,
                      TAKE PROFIT BY 100
                    }
                    """.trimIndent(),
                prices = listOf("100", "102", "112", "112", "112", "112", "80", "80"),
                expectedSides = listOf("BUY", "SELL"),
            ),
        )

    @TestFactory
    fun `generated composite orders match ticks bars and live paper`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> =
        cases.map { case ->
            DynamicTest.dynamicTest(case.id) {
                val path = tempDir.resolve("${case.id}.qkt")
                Files.writeString(path, strategySource(case))

                val result =
                    GeneratedStrategyReplay.assertTickBarAndLiveParity(
                        path = path,
                        closes = case.prices,
                        expectedTradeCount = case.expectedSides.size,
                    )

                assertThat(result.backtest.trades.map { it.side }).containsExactlyElementsOf(case.expectedSides)
            }
        }

    private fun strategySource(case: Case): String =
        """
        STRATEGY ${case.id} VERSION 1
        SYMBOLS x = BACKTEST:X EVERY 1m
        RULES
          WHEN x.close = 100 AND POSITION.x = 0
          THEN ${case.action.prependIndent("  ").trimStart()}
        """.trimIndent()
}
