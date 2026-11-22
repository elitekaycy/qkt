package com.qkt.dsl.compile

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.sets.SetCrossSections
import com.qkt.dsl.sets.SetFunction
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MovingSetFunctionCompilerTest {
    private val candle =
        Candle("X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)

    private fun at(ms: Long) =
        EvalContext(candle, emptyMap(), emptyMap(), strategyContext = testStrategyContext(), evaluationTimeMs = ms)

    private val sections = SetCrossSections { listOf("s.A", "s.B") }

    private fun call(
        fn: SetFunction,
        expr: ExprAst,
        site: Int = 0,
    ) = FuncCall(fn.moving, listOf(expr, StringLit("s.A"), NumLit(BigDecimal(site))))

    @Test
    fun `a member posts its own value at its close, and the call reduces the current members' cross-section`() {
        val exprs = ExprCompiler(crossSections = sections)
        val rank = exprs.compile(call(SetFunction.RANK_OF, NumLit(BigDecimal(3))))
        assertThat(rank.evaluate(at(60_000L))).isEqualTo(Value.Undefined)

        sections.post(0, "s.B", Value.Num(BigDecimal(5)))
        exprs.aggregates
            .bindingsForAlias("s.A")
            .single()
            .update(at(120_000L))
        assertThat(rank.evaluate(at(120_000L))).isEqualTo(Value.Num(BigDecimal(2)))

        val count =
            exprs.compile(
                call(
                    SetFunction.COUNT_OF,
                    com.qkt.dsl.ast
                        .BoolLit(true),
                    site = 1,
                ),
            )
        exprs.aggregates
            .bindingsForAlias("s.A")
            .last()
            .update(at(120_000L))
        assertThat(count.evaluate(at(120_000L))).isEqualTo(Value.Num(BigDecimal.ONE))
    }

    @Test
    fun `outside a member instance a moving call is refused`() {
        assertThatThrownBy { ExprCompiler().compile(call(SetFunction.SUM_OF, NumLit(BigDecimal.ONE))) }
            .hasMessage("SUM_OF(…) over a set whose members move runs only inside the set's member instances")
    }
}
