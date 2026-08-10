package com.qkt.dsl.compile

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.qkt.candles.TimeWindow
import com.qkt.dsl.ast.ActionAst
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory

class GeneratedActionParityTest {
    private data class Case(
        val id: String,
        val capability: String,
        val source: String,
        val closes: List<String>,
        val expectedSides: List<String>,
        val expectedQuantities: List<String>,
        val expectedLog: String? = null,
    )

    private val cases =
        listOf(
            Case(
                "buy",
                "Buy",
                singleAction("buy", "BUY x SIZING 1"),
                listOf("100", "100"),
                listOf("BUY"),
                listOf("1"),
            ),
            Case(
                "sell",
                "Sell",
                singleAction("sell", "SELL x SIZING 1"),
                listOf("100", "100"),
                listOf("SELL"),
                listOf("1"),
            ),
            Case(
                "close",
                "Close",
                lifecycleSource("close", "CLOSE x"),
                listOf("100", "100", "110", "110"),
                listOf("BUY", "SELL"),
                listOf("1", "1"),
            ),
            Case(
                "resize",
                "Resize",
                """
                STRATEGY resize VERSION 1
                SYMBOLS x = $SYMBOL EVERY 1m
                RULES
                  WHEN x.close = 100 AND POSITION.x = 0 THEN BUY x SIZING 1
                  WHEN x.close = 110 THEN RESIZE x TO 3
                  WHEN x.close = 120 THEN RESIZE x TO 1
                  WHEN x.close = 130 THEN RESIZE x TO 0
                """.trimIndent(),
                listOf("100", "100", "110", "120", "130", "130"),
                listOf("BUY", "BUY", "SELL", "SELL"),
                listOf("1", "2", "2", "1"),
            ),
            Case(
                "close_all",
                "CloseAll",
                lifecycleSource("close_all", "CLOSE_ALL"),
                listOf("100", "100", "110", "110"),
                listOf("BUY", "SELL"),
                listOf("1", "1"),
            ),
            Case(
                "cancel",
                "Cancel",
                cancellationSource("cancel", "CANCEL x"),
                listOf("100", "100", "101", "101", "89", "89"),
                emptyList(),
                emptyList(),
            ),
            Case(
                "cancel_all",
                "CancelAll",
                cancellationSource("cancel_all", "CANCEL_ALL"),
                listOf("100", "100", "101", "101", "89", "89"),
                emptyList(),
                emptyList(),
            ),
            Case(
                "log",
                "Log",
                singleAction("log", "LOG 'action price={price}' price=x.close; BUY x SIZING 1"),
                listOf("100", "100"),
                listOf("BUY"),
                listOf("1"),
                expectedLog = "action price=100",
            ),
            Case(
                "block",
                "Block",
                singleAction("block", "BUY x SIZING 1; BUY x SIZING 2"),
                listOf("100", "100"),
                listOf("BUY", "BUY"),
                listOf("1", "2"),
            ),
            Case(
                "oco_entry",
                "OcoEntry",
                singleAction(
                    "oco_entry",
                    """
                    OCO_ENTRY {
                      BUY x SIZING 1 ORDER_TYPE = STOP AT 105,
                      SELL x SIZING 1 ORDER_TYPE = STOP AT 95
                    }
                    """.trimIndent(),
                ),
                listOf("100", "100", "106", "106"),
                listOf("BUY"),
                listOf("1"),
            ),
            Case(
                "latch",
                "Latch",
                singleAction(
                    "latch",
                    """
                    LATCH x OFFSET 1 ARM 5m CONFIRM CLOSE_BEYOND {
                      ENTER MARKET SIZING 1
                    }
                    """.trimIndent(),
                ),
                listOf("100", "100", "102", "102"),
                listOf("BUY"),
                listOf("1"),
            ),
        )

    @TestFactory
    fun `every action drives generated tick bar and live behavior`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> {
        assertThat(cases.map { it.capability })
            .doesNotHaveDuplicates()
            .containsExactlyInAnyOrderElementsOf(ActionAst::class.java.permittedSubclasses.map { it.simpleName })

        return cases.map { case ->
            DynamicTest.dynamicTest(case.capability) {
                val path = tempDir.resolve("${case.id}.qkt")
                Files.writeString(path, case.source)
                val appender = case.expectedLog?.let(::attachStrategyLog)
                try {
                    val result =
                        GeneratedStrategyReplay.assertTickBarAndLiveParity(
                            path = path,
                            candlesBySymbol = mapOf(SYMBOL to candles(case.closes)),
                            window = TimeWindow.ONE_MINUTE,
                            expectedTradeCount = case.expectedSides.size,
                        )

                    assertThat(result.backtest.trades.map { it.side }).containsExactlyElementsOf(case.expectedSides)
                    assertThat(result.backtest.trades.map { it.quantity })
                        .containsExactlyElementsOf(case.expectedQuantities)
                    case.expectedLog?.let { expected ->
                        assertThat(requireNotNull(appender).list.map { it.formattedMessage }).contains(expected)
                    }
                } finally {
                    appender?.let(::detachStrategyLog)
                }
            }
        }
    }

    private fun attachStrategyLog(expected: String): ListAppender<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>()
        appender.name = expected
        appender.start()
        strategyLogger.addAppender(appender)
        return appender
    }

    private fun detachStrategyLog(appender: ListAppender<ILoggingEvent>) {
        strategyLogger.detachAppender(appender)
        appender.stop()
    }

    private companion object {
        const val SYMBOL = "BACKTEST:X"
        val strategyLogger = LoggerFactory.getLogger("com.qkt.dsl.strategy") as Logger

        fun singleAction(
            id: String,
            action: String,
        ): String =
            """
            STRATEGY $id VERSION 1
            SYMBOLS x = $SYMBOL EVERY 1m
            RULES
              WHEN x.close = 100 AND POSITION.x = 0
              THEN ${action.prependIndent("  ").trimStart()}
            """.trimIndent()

        fun lifecycleSource(
            id: String,
            closingAction: String,
        ): String =
            """
            STRATEGY $id VERSION 1
            SYMBOLS x = $SYMBOL EVERY 1m
            RULES
              WHEN x.close = 100 AND POSITION.x = 0 THEN BUY x SIZING 1
              WHEN x.close = 110 AND POSITION.x != 0 THEN $closingAction
            """.trimIndent()

        fun cancellationSource(
            id: String,
            cancelAction: String,
        ): String =
            """
            STRATEGY $id VERSION 1
            SYMBOLS x = $SYMBOL EVERY 1m
            RULES
              WHEN x.close = 100 AND POSITION.x = 0 AND OPEN_ORDERS.x = 0
              THEN BUY x SIZING 1 ORDER_TYPE = LIMIT AT 90
              WHEN x.close = 101 THEN $cancelAction
            """.trimIndent()

        fun candles(closes: List<String>): List<Candle> =
            closes.mapIndexed { index, close ->
                val price = BigDecimal(close)
                val start = index * 60_000L
                Candle(
                    symbol = SYMBOL,
                    open = price,
                    high = price,
                    low = price,
                    close = price,
                    volume = BigDecimal.ONE,
                    startTime = start,
                    endTime = start + 60_000L,
                )
            }
    }
}
