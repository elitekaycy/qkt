package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A BRACKET must end up with both legs once DEFAULTS are merged, on rules, schedules and LATCH entries. */
class CompleteBracketCheckTest {
    private fun ast(
        body: String,
        defaults: String = "",
    ) = (
        Dsl.parse(
            "STRATEGY s VERSION 1\n${defaults}SYMBOLS\n    b = BACKTEST:BTCUSDT EVERY 1m\n$body\n",
        ) as ParseResult.Success
    ).value

    @Test
    fun `a latch entry bracket with only a stop loss is incomplete`() {
        val strategy =
            ast(
                "RULES\n    WHEN b.close > 0 THEN LATCH b OFFSET 1 ARM 5m " +
                    "{ ENTER MARKET BRACKET { STOP LOSS AGAINST 8 } }",
            )

        assertThatThrownBy { validateCompleteBrackets(strategy) }
            .hasMessageContaining("BRACKET requires both STOP LOSS and TAKE PROFIT; missing TAKE PROFIT")
    }

    @Test
    fun `a target-only bracket on a scheduled entry is incomplete without a default stop`() {
        val schedule = "SCHEDULE AT 09:00 UTC THEN BUY b SIZING 1 BRACKET { TAKE_PROFIT BY 5 }"
        val strategy = ast(schedule)
        val withDefault = ast(schedule, defaults = "DEFAULTS {\n    STOP_LOSS = BY 3\n}\n")

        assertThatThrownBy { validateCompleteBrackets(strategy) }
            .hasMessageContaining("missing STOP LOSS after DEFAULTS merge")
        assertThatCode { validateCompleteBrackets(withDefault) }.doesNotThrowAnyException()
    }

    @Test
    fun `a rule bracket missing its target is reported`() {
        val one = NumLit(BigDecimal.ONE)
        val action = Buy("b", ActionOpts(sizing = SizeQty(one), bracket = BracketAst(ChildBy(one), null)))

        assertThatThrownBy { validateCompleteBracket(action, null) }
            .hasMessageContaining("missing TAKE PROFIT after DEFAULTS merge")
    }
}
