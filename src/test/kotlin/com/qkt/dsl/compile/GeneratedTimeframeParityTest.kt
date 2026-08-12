package com.qkt.dsl.compile

import com.qkt.candles.TimeWindow
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

class GeneratedTimeframeParityTest {
    @TestFactory
    fun `every supported timeframe drives tick bar and live behavior`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> =
        VALID_SPECS.map { spec ->
            DynamicTest.dynamicTest(spec) {
                val window = TimeWindow.parse(spec)
                val path = tempDir.resolve("timeframe-$spec.qkt")
                Files.writeString(path, strategy(spec))

                val result =
                    GeneratedStrategyReplay.assertTickBarAndLiveParity(
                        path = path,
                        candlesBySymbol = mapOf(SYMBOL to candles(window)),
                        window = window,
                        expectedTradeCount = 1,
                    )

                val trade = result.backtest.trades.single()
                assertThat(window.canonicalSpec()).isEqualTo(spec)
                assertThat(trade.quantity).isEqualTo("1")
            }
        }

    @Test
    fun `millisecond timeframe is rejected explicitly`() {
        val unsupported = INVALID_SPEC.removePrefix("invalid-")
        val source = strategy(unsupported)

        assertThat(VALID_SPECS + INVALID_SPEC)
            .containsExactlyInAnyOrderElementsOf(catalogTimeframes())
        assertThat(Dsl.parse(source)).isInstanceOf(ParseResult.Failure::class.java)
        assertThatThrownBy { TimeWindow.parse(unsupported) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("positive integer")
    }

    private companion object {
        const val SYMBOL = "BACKTEST:X"
        const val INVALID_SPEC = "invalid-5ms"
        val VALID_SPECS = listOf("1s", "5s", "1m", "2m", "5m", "15m", "1h", "4h", "1d")

        fun catalogTimeframes(): List<String> {
            val resource =
                requireNotNull(
                    GeneratedTimeframeParityTest::class.java
                        .getResourceAsStream("/validation/capability-catalog.json"),
                )

            return resource
                .bufferedReader()
                .use { Json.parseToJsonElement(it.readText()).jsonObject }
                .getValue("categories")
                .jsonObject
                .getValue("timeframes")
                .jsonObject
                .getValue("capabilities")
                .jsonArray
                .map { it.jsonPrimitive.content }
        }

        fun strategy(spec: String): String =
            """
            STRATEGY timeframe_${spec.replace("ms", "millis")} VERSION 1
            SYMBOLS x = $SYMBOL EVERY $spec
            RULES
              WHEN x.close = 100 AND POSITION.x = 0
              THEN BUY x SIZING 1
            """.trimIndent()

        fun candles(window: TimeWindow): List<Candle> =
            listOf("100", "100").mapIndexed { index, close ->
                val start = index * window.durationMs
                val price = BigDecimal(close)
                Candle(
                    symbol = SYMBOL,
                    open = price,
                    high = price,
                    low = price,
                    close = price,
                    volume = BigDecimal.ONE,
                    startTime = start,
                    endTime = start + window.durationMs,
                )
            }
    }
}
