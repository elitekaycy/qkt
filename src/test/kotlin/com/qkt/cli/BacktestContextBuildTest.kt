package com.qkt.cli

import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BacktestContextBuildTest {
    private val ast: StrategyAst =
        (
            Dsl.parse(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    gold = BACKTEST:XAUUSD EVERY 15m
                RULES
                    WHEN gold.close > 0 THEN BUY gold SIZING 0.1
                """.trimIndent(),
            ) as ParseResult.Success
        ).value

    @Test
    fun `--symbols may only narrow the symbols the strategy declares`() {
        val args = Args(arrayOf("backtest", "s.qkt", "--from", "2024-01-02", "--to", "2024-01-03", "--symbols", "X:Y"))
        assertThatThrownBy { BacktestContext.build(args, ast) }
            .isInstanceOf(BacktestContext.Companion.SetupError::class.java)
            .hasMessage("--symbols contains unknown symbols [X:Y]; strategy declares [BACKTEST:XAUUSD]")
    }
}
