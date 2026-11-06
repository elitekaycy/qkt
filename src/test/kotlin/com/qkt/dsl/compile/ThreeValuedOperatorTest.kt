package com.qkt.dsl.compile

import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Operators over undefined or mismatched values follow three-valued logic instead of failing. */
class ThreeValuedOperatorTest {
    private val one = BigDecimal.ONE
    private val ctx =
        EvalContext(
            Candle("BACKTEST:XAUUSD", one, one, one, one, BigDecimal.ZERO, 0L, 1L),
            mapOf("g" to HubKey("BACKTEST", "XAUUSD", "1m")),
            emptyMap(),
            testStrategyContext(),
        )

    /** The bar has no bid, so `g.bid` is undefined. */
    private val undefined = StreamFieldRef("g", "bid")

    private fun eval(e: ExprAst): Value = ExprCompiler().compile(e).evaluate(ctx)

    @Test
    fun `OR is true when either side is true and undefined otherwise when one side is unknown`() {
        assertThat(
            eval(BinaryOp(BinOp.OR, CmpOp(Cmp.GT, undefined, NumLit(one)), BoolLit(true))),
        ).isEqualTo(Value.of(true))
        assertThat(
            eval(BinaryOp(BinOp.OR, BoolLit(false), CmpOp(Cmp.GT, undefined, NumLit(one)))),
        ).isEqualTo(Value.Undefined)
        assertThat(eval(BinaryOp(BinOp.OR, BoolLit(false), BoolLit(false)))).isEqualTo(Value.of(false))
    }

    @Test
    fun `negation of an undefined or non-matching value is undefined`() {
        assertThat(eval(UnaryOp(UnOp.NEG, undefined))).isEqualTo(Value.Undefined)
        assertThat(eval(UnaryOp(UnOp.NOT, NumLit(one)))).isEqualTo(Value.Undefined)
    }

    @Test
    fun `strings compare only for equality and inequality`() {
        assertThat(eval(CmpOp(Cmp.NE, StringLit("a"), StringLit("b")))).isEqualTo(Value.of(true))
        assertThat(eval(CmpOp(Cmp.EQ, StringLit("a"), StringLit("b")))).isEqualTo(Value.of(false))
        assertThat(eval(CmpOp(Cmp.GT, StringLit("a"), StringLit("b")))).isEqualTo(Value.Undefined)
        assertThat(eval(CmpOp(Cmp.EQ, StringLit("a"), NumLit(one)))).isEqualTo(Value.Undefined)
    }
}
