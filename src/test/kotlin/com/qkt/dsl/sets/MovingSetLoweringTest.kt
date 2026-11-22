package com.qkt.dsl.sets

import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.ForEachSet
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MovingSetLoweringTest {
    private fun loops(rules: String): List<ForEachSet> =
        (
            Dsl.parse(
                """
                |STRATEGY s VERSION 1
                |SYMBOLS
                |    s = BACKTEST:* WHERE kind = event TOP 2 BY symbol DYNAMIC EVERY 1m
                |RULES
                |$rules
                """.trimMargin(),
            ) as ParseResult.Success
        ).value.rules
            .filterIsInstance<ForEachSet>()

    private fun lhs(rule: WhenThen): Any = (rule.cond as CmpOp).lhs

    private fun moving(
        fn: SetFunction,
        site: Int,
    ) = FuncCall(fn.moving, listOf(StreamFieldRef("m", "close"), StringLit("s.A"), NumLit(BigDecimal(site))))

    @Test
    fun `each call over the moving set keeps its expression once and is numbered by site, in every loop`() {
        val loops =
            loops(
                """
                |    FOR EACH m IN s DO WHEN RANK_OF(m.close, s) = 1 AND abs(m.close) > 0 THEN BUY m SIZING 1
                |    FOR EACH m IN s DO WHEN SUM_OF(m.close, s) > 2 THEN CLOSE m
                """.trimMargin(),
            )
        var site = 0
        val lowered = loops.map { SetFunctionLowering.lowerMoving(it, "s.A") { site++ } }
        val and = lowered[0].cond as com.qkt.dsl.ast.BinaryOp
        assertThat((and.lhs as CmpOp).lhs).isEqualTo(moving(SetFunction.RANK_OF, 0))
        assertThat((and.rhs as CmpOp).lhs).isEqualTo(FuncCall("ABS", listOf(StreamFieldRef("m", "close"))))
        assertThat(lhs(lowered[1])).isEqualTo(moving(SetFunction.SUM_OF, 1))
        assertThat(SetFunction.moving("SUM_OF.MOVING")).isEqualTo(SetFunction.SUM_OF)
        assertThat(SetFunction.moving("SUM_OF.SET")).isNull()
    }

    @Test
    fun `a call over the moving set that cannot mean one value per member is refused`() {
        val nested = loops("    FOR EACH m IN s DO WHEN SUM_OF(RANK_OF(m.close, s), s) > 1 THEN BUY m SIZING 1")
        assertThatThrownBy { SetFunctionLowering.lowerMoving(nested.single(), "s.A") { 0 } }
            .isInstanceOf(SymbolSetError::class.java)
            .hasMessage("SUM_OF(…, s): a cross-member function cannot be nested inside another")
    }

    @Test
    fun `NEXT_STRIKE and PREV_STRIKE keep their expression and are numbered with the set functions`() {
        val loop =
            loops(
                "    FOR EACH m IN s DO WHEN SUM_OF(m.close, s) > NEXT_STRIKE(m.close) - prev_strike(m.close) " +
                    "THEN BUY m SIZING 1",
            ).single()
        var site = 0
        val cond = SetFunctionLowering.lowerMoving(loop, "s.A") { site++ }.cond as CmpOp
        assertThat(cond.lhs).isEqualTo(moving(SetFunction.SUM_OF, 0))
        val own = listOf(StreamFieldRef("m", "close"), StringLit("s.A"))
        assertThat(cond.rhs).isEqualTo(
            com.qkt.dsl.ast.BinaryOp(
                com.qkt.dsl.ast.BinOp.SUB,
                FuncCall("NEXT_STRIKE.MOVING", own + NumLit(BigDecimal(1))),
                FuncCall("PREV_STRIKE.MOVING", own + NumLit(BigDecimal(2))),
            ),
        )
        assertThat(StrikeNeighbour.moving("PREV_STRIKE.MOVING")).isEqualTo(StrikeNeighbour.PREV_STRIKE)
        assertThat(StrikeNeighbour.moving("PREV_STRIKE")).isNull()
    }

    @Test
    fun `a strike call that cannot name one neighbour's value is refused`() {
        fun refusal(cond: String): String? =
            runCatching {
                SetFunctionLowering.lowerMoving(
                    loops("    FOR EACH m IN s DO WHEN $cond THEN BUY m SIZING 1").single(),
                    "s.A",
                ) { 0 }
            }.exceptionOrNull()?.message
        assertThat(
            refusal("NEXT_STRIKE(m.close, s) > 0"),
        ).isEqualTo("NEXT_STRIKE(…): takes one expression, got 2 arguments")
        assertThat(
            refusal("PREV_STRIKE(1) > 0"),
        ).isEqualTo("PREV_STRIKE(…): the expression must use the loop variable 'm'")
        assertThat(refusal("NEXT_STRIKE(PREV_STRIKE(m.close)) > 0"))
            .isEqualTo("NEXT_STRIKE(…): a cross-member function cannot be nested inside another")
    }
}
