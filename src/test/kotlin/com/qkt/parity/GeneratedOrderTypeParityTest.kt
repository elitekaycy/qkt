package com.qkt.parity

import com.qkt.common.Money
import com.qkt.dsl.compile.GeneratedStrategyReplay
import com.qkt.marketdata.Tick
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

class GeneratedOrderTypeParityTest {
    private data class Case(
        val id: String,
        val orderType: String? = null,
        val prices: List<String>,
        val expectedSides: List<String>,
        val source: String? = null,
    )

    private val cases =
        listOf(
            Case("market", "MARKET", listOf("100", "100"), listOf("BUY")),
            Case("limit", "LIMIT AT 95", listOf("100", "100", "98", "94"), listOf("BUY")),
            Case("stop", "STOP AT 105", listOf("100", "100", "103", "106"), listOf("BUY")),
            Case(
                "stop_limit",
                "STOP AT 105 LIMIT AT 106",
                listOf("100", "100", "103", "105"),
                listOf("BUY"),
            ),
            Case(
                "trailing_by",
                "TRAILING BY 10",
                listOf("100", "100", "95", "90", "80", "82", "88", "95"),
                listOf("BUY"),
            ),
            Case(
                "trailing_pct",
                "TRAILING PCT 5",
                listOf("100", "100", "95", "90", "92", "95"),
                listOf("BUY"),
            ),
            Case(
                id = "exit_relative_limit",
                prices = listOf("100", "100", "111", "110", "106"),
                expectedSides = listOf("BUY", "SELL", "BUY"),
                source = exitHookSource("exit_relative_limit", "LIMIT WITH 5"),
            ),
            Case(
                id = "exit_relative_stop",
                prices = listOf("100", "100", "111", "112", "117"),
                expectedSides = listOf("BUY", "SELL", "BUY"),
                source = exitHookSource("exit_relative_stop", "STOP AGAINST 5"),
            ),
        )

    @TestFactory
    fun `generated order types match ticks bars and live paper`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> =
        cases.map { case ->
            DynamicTest.dynamicTest(case.id) {
                val source = case.source ?: entrySource(case.id, requireNotNull(case.orderType))
                val path = tempDir.resolve("${case.id}.qkt")
                Files.writeString(path, source)

                GeneratedStrategyReplay.assertTickAndBarParity(
                    path = path,
                    closes = case.prices,
                    expectedTradeCount = case.expectedSides.size,
                )

                val replayPrices = case.prices + case.prices.last()
                val result = DslParityHarness.run(case.id, Files.readString(path), ticks(replayPrices))
                assertThat(result.live).isEqualTo(result.backtest)
                assertThat(result.backtest.trades.map { it.side }).containsExactlyElementsOf(case.expectedSides)
            }
        }

    private fun entrySource(
        id: String,
        orderType: String,
    ): String =
        """
        STRATEGY $id VERSION 1
        SYMBOLS x = BACKTEST:X EVERY 1m
        RULES
          WHEN x.close = 100 AND POSITION.x = 0
          THEN BUY x SIZING 0.01 ORDER_TYPE = $orderType
        """.trimIndent()

    private fun exitHookSource(
        id: String,
        orderType: String,
    ): String =
        """
        STRATEGY $id VERSION 1
        SYMBOLS x = BACKTEST:X EVERY 1m
        RULES
          WHEN x.close = 100 AND POSITION.x = 0
          THEN BUY x SIZING 0.01
            BRACKET { STOP LOSS BY 20, TAKE PROFIT BY 10 }
            ON_TP {
              BUY x SIZING EXIT.qty ORDER_TYPE = $orderType
            }
        """.trimIndent()

    private fun ticks(prices: List<String>): List<Tick> =
        prices.mapIndexed { index, price ->
            Tick("BACKTEST:X", Money.of(price), index * 60_000L)
        }
}
