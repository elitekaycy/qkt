package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.SizeQty
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** An entry cannot carry both a BRACKET and an OCO, and an entry on an undeclared stream fails when it fires. */
class EntryOrderShapeTest {
    private val one = NumLit(BigDecimal.ONE)
    private val size = SizeQty(one)
    private val ctx =
        EvalContext(
            Candle(
                "BACKTEST:XAUUSD",
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.ZERO,
                0L,
                1L,
            ),
            mapOf("g" to HubKey("BACKTEST", "XAUUSD", "1m")),
            emptyMap(),
            testStrategyContext(),
        )

    @Test
    fun `a bracket and an OCO on one entry are refused`() {
        val opts =
            ActionOpts(
                sizing = size,
                bracket = BracketAst(ChildBy(one), ChildBy(one)),
                oco = OcoAst(ChildAt(one), ChildAt(one)),
            )

        assertThatThrownBy { ActionCompiler(ExprCompiler()).compile(Buy("g", opts)) }
            .hasMessageContaining("Cannot combine BRACKET and OCO on the same action")
    }

    @Test
    fun `a bracketed entry on an undeclared stream fails when it fires`() {
        val opts = ActionOpts(sizing = size, bracket = BracketAst(ChildBy(one), ChildBy(one)))

        assertThatThrownBy { ActionCompiler(ExprCompiler()).compile(Buy("copper", opts))(ctx) }
            .hasMessageContaining("Unknown stream alias: copper")
    }
}
