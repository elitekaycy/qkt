package com.qkt.dsl.parse

import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ExitField
import com.qkt.dsl.ast.ExitRef
import com.qkt.dsl.ast.ExitRelativeLimit
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.WhenThen
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserExitHookTest {
    private fun parseAction(action: String): Buy {
        val source =
            """
            STRATEGY hooks VERSION 1
            SYMBOLS gold = BACKTEST:XAUUSD EVERY 1m
            RULES
              WHEN gold.close > 0 THEN $action
            """.trimIndent()
        val result = Parser(Lexer(source).tokenize()).parseStrategy() as ParseResult.Success
        return (result.value.rules.single() as WhenThen).action as Buy
    }

    @Test
    fun `all exit hooks parse and preserve child actions`() {
        val action =
            parseAction(
                """
                BUY gold SIZING 1 BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 10 }
                  ON_STOP { SELL gold SIZING 2 BRACKET { STOP LOSS BY 3, TAKE PROFIT BY 6 } }
                  ON_TP { BUY gold SIZING EXIT.qty ORDER_TYPE = LIMIT AT EXIT.price - 2 TIF GTD UNTIL NOW + 2h }
                  ON_CLOSE { SELL gold SIZING 1 }
                """.trimIndent(),
            )

        val hooks = action.opts.exitHooks
        val takeProfit = hooks.onTakeProfit.single() as Buy
        val takeProfitSizing = takeProfit.opts.sizing as SizeQty
        val limit = takeProfit.opts.orderType as Limit

        assertThat(hooks.onStop.single()).isInstanceOf(Sell::class.java)
        assertThat(takeProfitSizing.expr).isEqualTo(ExitRef(ExitField.QTY))
        assertThat(limit.price).isInstanceOf(BinaryOp::class.java)
        assertThat(hooks.onClose.single()).isInstanceOf(Sell::class.java)
    }

    @Test
    fun `hook pending price accepts direction-relative notation`() {
        val action =
            parseAction(
                "BUY gold SIZING 1 ON_STOP { BUY gold SIZING 1 ORDER_TYPE = LIMIT AGAINST 30 }",
            )

        val stopHook =
            action
                .opts
                .exitHooks
                .onStop
                .single() as Buy

        assertThat(stopHook.opts.orderType).isInstanceOf(ExitRelativeLimit::class.java)
    }

    @Test
    fun `exit accessor parses outside hook for a precise compiler error`() {
        val source =
            """
            STRATEGY hooks VERSION 1
            SYMBOLS gold = BACKTEST:XAUUSD EVERY 1m
            RULES
              WHEN EXIT.price > 0 THEN BUY gold SIZING 1
            """.trimIndent()

        assertThat(Parser(Lexer(source).tokenize()).parseStrategy())
            .isInstanceOf(ParseResult.Success::class.java)
    }

    @Test
    fun `duplicate hook kind is rejected instead of silently replacing actions`() {
        val source =
            """
            STRATEGY hooks VERSION 1
            SYMBOLS gold = BACKTEST:XAUUSD EVERY 1m
            RULES
              WHEN gold.close > 0 THEN BUY gold SIZING 1
                ON_STOP { SELL gold SIZING 1 }
                ON_STOP { BUY gold SIZING 1 }
            """.trimIndent()

        val failure = Parser(Lexer(source).tokenize()).parseStrategy() as ParseResult.Failure
        assertThat(failure.errors.map { it.message }).anyMatch { it.contains("duplicate ON_STOP clause") }
    }
}
