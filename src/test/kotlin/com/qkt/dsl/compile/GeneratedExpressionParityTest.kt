package com.qkt.dsl.compile

import com.qkt.candles.TimeWindow
import com.qkt.dsl.ast.ExprAst
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

class GeneratedExpressionParityTest {
    private data class Case(
        val id: String,
        val capability: String,
        val source: String,
        val candles: List<Candle> = candles("100", "100"),
        val expectedQuantities: List<String> = listOf("1"),
    )

    private val cases =
        listOf(
            expressionCase("num_lit", "NumLit", "2", "probe = 2"),
            expressionCase("bool_lit", "BoolLit", "TRUE", "probe"),
            expressionCase("string_lit", "StringLit", "\"ready\"", "probe = \"ready\""),
            expressionCase("ref", "Ref", "threshold", "probe = 100", extraLets = "threshold = 100"),
            expressionCase("stream_field", "StreamFieldRef", "x.close", "probe = 100"),
            expressionCase("now", "NowAccessor", "NOW.epoch_ms", "probe >= 60000"),
            expressionCase("calendar_window", "CalendarWindow", "CALENDAR_WINDOW(1, 1, 1, 2)", "probe"),
            expressionCase("session_window", "SessionWindow", "SESSION_WINDOW(0, 0, 0, 2)", "probe"),
            expressionCase(
                "last_trading_day",
                "LastTradingDayOfMonth",
                "LAST_TRADING_DAY_OF_MONTH()",
                "probe",
                candles = candles("100", "100", startTime = Instant.parse("1970-01-30T00:00:00Z").toEpochMilli()),
            ),
            expressionCase(
                "indicator_call",
                "IndicatorCall",
                "EMA(x.close, 2)",
                "probe > 0",
                candles = candles("100", "100", "100"),
            ),
            expressionCase("binary_op", "BinaryOp", "x.close + 2", "probe = 102"),
            expressionCase("unary_op", "UnaryOp", "NOT FALSE", "probe"),
            expressionCase("cmp_op", "CmpOp", "x.close = 100", "probe"),
            expressionCase("between", "Between", "x.close BETWEEN 99 AND 101", "probe"),
            expressionCase("in_list", "InList", "x.close IN [99, 100, 101]", "probe"),
            expressionCase(
                "crosses",
                "Crosses",
                "x.close CROSSES ABOVE 100",
                "probe",
                candles = candles("99", "101", "101"),
            ),
            expressionCase(
                "case_when",
                "CaseWhen",
                "CASE WHEN x.close = 100 THEN 7 ELSE 0 END",
                "probe = 7",
            ),
            expressionCase(
                "aggregate",
                "Aggregate",
                "MAX(x.close) SINCE T-2",
                "probe >= 101",
                candles = candles("99", "101", "101"),
            ),
            expressionCase("account_ref", "AccountRef", "ACCOUNT.balance", "probe = 10000"),
            expressionCase("streak_ref", "StreakRef", "STREAK.banked", "probe = 0"),
            expressionCase("trades_ref", "TradesRef", "TRADES.today", "probe = 0"),
            expressionCase("cooldown_ref", "CooldownRef", "COOLDOWN.remaining_s", "probe = 0"),
            expressionCase("position_ref", "PositionRef", "POSITION.x", "probe = 0"),
            expressionCase("state_accessor", "StateAccessor", "OPEN_ORDERS.x", "probe = 0"),
            expressionCase("func_call", "FuncCall", "ABS(x.close - 101)", "probe = 1"),
            expressionCase(
                "is_null",
                "IsNull",
                "ACCOUNT.last_trade_pnl IS NULL",
                "probe",
            ),
            stackEntryCase(),
            entryQtyCase(),
            exitRefCase(),
            sequenceCase(),
        )

    @TestFactory
    fun `every expression drives generated tick bar and live behavior`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> {
        assertThat(cases.map { it.capability })
            .doesNotHaveDuplicates()
            .containsExactlyInAnyOrderElementsOf(ExprAst::class.java.permittedSubclasses.map { it.simpleName })

        return cases.map { case ->
            DynamicTest.dynamicTest(case.capability) {
                val path = tempDir.resolve("${case.id}.qkt")
                Files.writeString(path, case.source)

                val result =
                    GeneratedStrategyReplay.assertTickBarAndLiveParity(
                        path = path,
                        candlesBySymbol = mapOf(SYMBOL to case.candles),
                        window = TimeWindow.ONE_MINUTE,
                        expectedTradeCount = case.expectedQuantities.size,
                        startingBalance = BigDecimal("10000"),
                    )

                assertThat(result.backtest.trades.map { it.quantity })
                    .containsExactlyElementsOf(case.expectedQuantities)
            }
        }
    }

    private companion object {
        const val SYMBOL = "BACKTEST:X"

        fun expressionCase(
            id: String,
            capability: String,
            expression: String,
            condition: String,
            extraLets: String = "",
            candles: List<Candle> = candles("100", "100"),
        ): Case =
            Case(
                id = id,
                capability = capability,
                source =
                    """
                    STRATEGY $id VERSION 1
                    SYMBOLS x = $SYMBOL EVERY 1m
                    LET ${if (extraLets.isBlank()) "probe = $expression" else "$extraLets, probe = $expression"}
                    RULES
                      WHEN $condition AND POSITION.x = 0
                      THEN BUY x SIZING 1
                    """.trimIndent(),
                candles = candles,
            )

        fun stackEntryCase(): Case =
            Case(
                id = "stack_entry_ref",
                capability = "StackEntryRef",
                source =
                    """
                    STRATEGY stack_entry_ref VERSION 1
                    SYMBOLS x = $SYMBOL EVERY 1m
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0
                      THEN BUY x STACK [ 1, 0.5 AT entry + 5 ]
                    """.trimIndent(),
                candles = candles("100", "100", "105", "105"),
                expectedQuantities = listOf("1", "0.5"),
            )

        fun entryQtyCase(): Case =
            Case(
                id = "entry_qty",
                capability = "EntryQty",
                source =
                    """
                    STRATEGY entry_qty VERSION 1
                    SYMBOLS x = $SYMBOL EVERY 1m
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0
                      THEN BUY x SIZING 1
                        STACK_AT MFE >= 5 WITHIN 30m
                          SIZING ENTRY_QTY * 0.5
                          BRACKET { STOP LOSS BY 20, TAKE PROFIT BY 20 }
                    """.trimIndent(),
                candles = candles("100", "100", "106", "106"),
                expectedQuantities = listOf("1", "0.5"),
            )

        fun exitRefCase(): Case =
            Case(
                id = "exit_ref",
                capability = "ExitRef",
                source =
                    """
                    STRATEGY exit_ref VERSION 1
                    SYMBOLS x = $SYMBOL EVERY 1m
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0
                      THEN BUY x SIZING 1
                        BRACKET { STOP LOSS BY 20, TAKE PROFIT BY 5 }
                        ON_TP { BUY x SIZING EXIT.qty }
                    """.trimIndent(),
                candles = candles("100", "100", "106", "106"),
                expectedQuantities = listOf("1", "1", "1"),
            )

        fun sequenceCase(): Case =
            Case(
                id = "sequence_accessor",
                capability = "SequenceAccessor",
                source =
                    """
                    STRATEGY sequence_accessor VERSION 1
                    SYMBOLS x = $SYMBOL EVERY 1m
                    SEQUENCE sweep ON x {
                      STAGE swept: x.low < 99
                      STAGE reclaimed WITHIN 2m: x.close > 100
                    }
                    RULES
                      WHEN SEQUENCE.sweep.complete AND SEQUENCE.sweep.swept.price < 99
                      THEN BUY x SIZING 1
                    """.trimIndent(),
                candles =
                    listOf(
                        candle(0L, open = "100", high = "100", low = "98", close = "98"),
                        candle(60_000L, open = "98", high = "101", low = "98", close = "101"),
                        candle(120_000L, open = "101", high = "101", low = "101", close = "101"),
                    ),
            )

        fun candles(
            vararg closes: String,
            startTime: Long = 0L,
        ): List<Candle> =
            closes.mapIndexed { index, close ->
                candle(startTime + index * 60_000L, close, close, close, close)
            }

        fun candle(
            startTime: Long,
            open: String,
            high: String,
            low: String,
            close: String,
        ): Candle =
            Candle(
                symbol = SYMBOL,
                open = BigDecimal(open),
                high = BigDecimal(high),
                low = BigDecimal(low),
                close = BigDecimal(close),
                volume = BigDecimal.ONE,
                startTime = startTime,
                endTime = startTime + 60_000L,
            )
    }
}
