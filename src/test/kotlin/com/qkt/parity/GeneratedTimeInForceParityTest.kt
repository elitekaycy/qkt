package com.qkt.parity

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
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

class GeneratedTimeInForceParityTest {
    private data class Case(
        val id: String,
        val tif: String,
        val limit: String,
        val prices: List<String>,
        val startTime: Long = 0L,
        val expectedTrades: Int,
    )

    private val cases =
        listOf(
            Case("gtc", "GTC", "95", listOf("100", "100", "94", "94"), expectedTrades = 1),
            Case("ioc", "IOC", "95", listOf("100", "100", "94", "94"), expectedTrades = 0),
            Case("fok", "FOK", "101", listOf("100", "100", "100"), expectedTrades = 1),
            Case(
                "gtd",
                "GTD NOW + 1m",
                "95",
                listOf("100", "100", "100", "94", "94"),
                expectedTrades = 0,
            ),
            Case(
                "day",
                "DAY",
                "95",
                listOf("100", "100", "100", "94", "94"),
                startTime = 86_400_000L - 120_000L,
                expectedTrades = 0,
            ),
        )

    @TestFactory
    fun `generated time in force lifecycles match ticks bars and live paper`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> =
        cases.map { case ->
            DynamicTest.dynamicTest(case.id) {
                val path = tempDir.resolve("${case.id}.qkt")
                Files.writeString(path, strategySource(case))
                val candles = candles(case)

                GeneratedStrategyReplay.assertTickAndBarParity(
                    path = path,
                    candlesBySymbol = mapOf("BACKTEST:X" to candles),
                    window = TimeWindow.ONE_MINUTE,
                    closeOnlyTicks = true,
                    expectedTradeCount = case.expectedTrades,
                )

                val result = DslParityHarness.run(case.id, Files.readString(path), ticks(candles))
                assertThat(result.live).isEqualTo(result.backtest)
                assertThat(result.backtest.trades).hasSize(case.expectedTrades)
            }
        }

    private fun strategySource(case: Case): String =
        """
        STRATEGY ${case.id} VERSION 1
        SYMBOLS x = BACKTEST:X EVERY 1m
        RULES
          WHEN x.close = 100 AND POSITION.x = 0
          THEN BUY x SIZING 0.01 ORDER_TYPE = LIMIT AT ${case.limit} TIF ${case.tif}
        """.trimIndent()

    private fun candles(case: Case): List<Candle> =
        case.prices.mapIndexed { index, price ->
            val value = BigDecimal(price)
            val start = case.startTime + index * 60_000L
            Candle(
                symbol = "BACKTEST:X",
                open = value,
                high = value,
                low = value,
                close = value,
                volume = BigDecimal.ONE,
                startTime = start,
                endTime = start + 60_000L,
            )
        }

    private fun ticks(candles: List<Candle>): List<Tick> =
        candles.map { candle ->
            Tick(candle.symbol, Money.of(candle.close.toPlainString()), candle.startTime, volume = candle.volume)
        }
}
