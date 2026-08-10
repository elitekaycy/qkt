package com.qkt.dsl.compile

import com.qkt.candles.TimeWindow
import com.qkt.dsl.stdlib.IndicatorRegistry
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

class GeneratedIndicatorStrategyTest {
    @TestFactory
    fun `every indicator drives a generated strategy through ticks and bars`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> {
        val candles = mapOf("BACKTEST:A" to candles("BACKTEST:A", 100), "BACKTEST:B" to candles("BACKTEST:B", 200))
        return IndicatorRegistry
            .names()
            .sorted()
            .map { name ->
                DynamicTest.dynamicTest(name) {
                    val path = tempDir.resolve("${name.lowercase()}.qkt")
                    Files.writeString(path, strategySource(name))

                    GeneratedStrategyReplay.assertTickAndBarParity(
                        path = path,
                        candlesBySymbol = candles,
                        window = TimeWindow.ONE_HOUR,
                    )
                }
            }
    }

    private fun strategySource(name: String): String =
        """
        STRATEGY generated_${name.lowercase()} VERSION 1
        SYMBOLS
          a = BACKTEST:A EVERY 1h
          b = BACKTEST:B EVERY 1h
        RULES
          WHEN ${indicatorCall(name)} IS NOT NULL AND POSITION.a = 0
          THEN BUY a SIZING 0.01
        """.trimIndent()

    private fun candles(
        symbol: String,
        base: Int,
    ): List<Candle> {
        val epoch = Instant.parse("2026-01-05T00:00:00Z").toEpochMilli()
        val hourMs = TimeWindow.ONE_HOUR.durationMs
        return (0 until 5).flatMap { day ->
            (0 until 16).map { hour ->
                val center = BigDecimal(base + day * 3 + hour * 2)
                val open = center.add(if (hour % 2 == 0) BigDecimal.ONE else BigDecimal("-1"))
                val close = center.add(if ((day + hour) % 3 == 0) BigDecimal("2") else BigDecimal("-2"))
                val start = epoch + (day * 24L + hour) * hourMs
                Candle(
                    symbol = symbol,
                    open = open,
                    high = open.max(close).add(BigDecimal("3")),
                    low = open.min(close).subtract(BigDecimal("3")),
                    close = close,
                    volume = BigDecimal(10 + day + hour),
                    startTime = start,
                    endTime = start + hourMs,
                )
            }
        }
    }
}
