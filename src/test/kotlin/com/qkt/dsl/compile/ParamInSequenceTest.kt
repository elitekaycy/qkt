package com.qkt.dsl.compile

import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** PARAMs reach SEQUENCE stages, DEFAULTS keep a trailing order type, rule errors carry the rule's line. */
class ParamInSequenceTest {
    private fun parse(source: String) = (Dsl.parse(source.trimIndent()) as ParseResult.Success).value

    @Test
    fun `a PARAM read inside a SEQUENCE stage takes the declared value`() {
        val strategy =
            AstCompiler().compile(
                parse(
                    """
                    STRATEGY s VERSION 1
                    SYMBOLS
                        g = BACKTEST:XAUUSD EVERY 1m
                    PARAM lvl = 100
                    SEQUENCE up ON g {
                        STAGE over: g.close > lvl
                        STAGE higher: g.close > lvl + 1
                    }
                    RULES
                        WHEN SEQUENCE.up.complete THEN BUY g SIZING 1
                    """,
                ),
            )
        val fired =
            listOf("99", "101", "102").mapIndexed { i, close ->
                val c = BigDecimal(close)
                val out = mutableListOf<Signal>()
                val candle = Candle("BACKTEST:XAUUSD", c, c, c, c, BigDecimal.ONE, i * 60_000L, (i + 1) * 60_000L - 1)
                strategy.onCandle(candle, testStrategyContext()) { out += it }
                out.size
            }

        assertThat(fired).containsExactly(0, 0, 1)
    }

    @Test
    fun `a DEFAULTS trailing clause parses to its order type`() {
        val ast =
            parse(
                """
                STRATEGY s VERSION 1
                DEFAULTS {
                    TRAILING = TRAILING BY 5
                }
                SYMBOLS
                    g = BACKTEST:XAUUSD EVERY 1m
                """,
            )

        assertThat(ast.defaults?.trailing).isInstanceOf(TrailingBy::class.java)
    }

    @Test
    fun `a compile error raised inside a rule is tagged with that rule's line once`() {
        val rule = WhenThen(BoolLit(true), CloseAll, line = 7)
        val located = CompileError("already placed", line = 3)

        assertThatThrownBy { compilingRule(rule) { throw CompileError("bad sizing") } }
            .isInstanceOfSatisfying(CompileError::class.java) { assertThat(it.line).isEqualTo(7) }
        assertThatThrownBy { compilingRule(rule) { throw located } }.isSameAs(located)
    }
}
