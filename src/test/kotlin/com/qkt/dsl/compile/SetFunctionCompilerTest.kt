package com.qkt.dsl.compile

import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SetFunctionCompilerTest {
    private val candle =
        Candle("X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)
    private val ctx = EvalContext(candle, emptyMap(), emptyMap(), strategyContext = testStrategyContext())

    private fun n(v: String): ExprAst = NumLit(BigDecimal(v))

    /** A member whose value is Undefined, as one still warming up reads. */
    private val cold: ExprAst = FuncCall("SQRT", listOf(n("-1")))

    private fun eval(
        name: String,
        vararg args: ExprAst,
    ): Value = ExprCompiler().compile(FuncCall(name, args.toList())).evaluate(ctx)

    private fun num(
        name: String,
        vararg args: ExprAst,
    ): BigDecimal = (eval(name, *args) as Value.Num).v

    @Test
    fun `RANK_OF ranks this member's value among the members, ties sharing, cold members left out`() {
        assertThat(num("RANK_OF.SET", n("3"), n("3"), n("5"), n("1"))).isEqualByComparingTo("2")
        assertThat(num("RANK_OF.SET", n("5"), n("5"), n("5"), n("1"))).isEqualByComparingTo("1")
        assertThat(num("RANK_OF.SET", n("3"), n("3"), cold, n("1"))).isEqualByComparingTo("1")
        assertThat(eval("RANK_OF.SET", cold, cold, n("5"))).isEqualTo(Value.Undefined)
    }

    @Test
    fun `SUM_OF, AVG_OF, MAX_OF and MIN_OF read the defined members only`() {
        assertThat(num("SUM_OF.SET", n("1.5"), cold, n("2.5"))).isEqualByComparingTo("4")
        assertThat(num("AVG_OF.SET", n("1"), cold, n("2"))).isEqualByComparingTo("1.5")
        assertThat(num("AVG_OF.SET", n("1"), n("1"), n("2")).setScale(6, java.math.RoundingMode.HALF_EVEN))
            .isEqualByComparingTo("1.333333")
        assertThat(num("MAX_OF.SET", n("1"), cold, n("7"), n("3"))).isEqualByComparingTo("7")
        assertThat(num("MIN_OF.SET", n("4"), cold, n("-2"), n("3"))).isEqualByComparingTo("-2")
        assertThat(num("SUM_OF.SET", n("9"))).isEqualByComparingTo("9")
    }

    @Test
    fun `an aggregate over no defined member is Undefined`() {
        for (name in listOf("SUM_OF.SET", "AVG_OF.SET", "MAX_OF.SET", "MIN_OF.SET")) {
            assertThat(eval(name, cold, cold)).describedAs(name).isEqualTo(Value.Undefined)
        }
    }

    @Test
    fun `COUNT_OF counts the members whose test is true, Undefined counting as not`() {
        assertThat(num("COUNT_OF.SET", BoolLit(true), BoolLit(false), cold, BoolLit(true))).isEqualByComparingTo("2")
        assertThat(num("COUNT_OF.SET", cold, BoolLit(false))).isEqualByComparingTo("0")
    }

    @Test
    fun `a set function the expansion did not lower is refused with the way to write it`() {
        assertThatThrownBy { ExprCompiler().compile(FuncCall("SUM_OF", listOf(n("1"), Ref("fx")))) }
            .hasMessageContaining("SUM_OF(expr, set) is a cross-member function: use it inside FOR EACH <m> IN <set>")
        assertThatThrownBy { ExprCompiler().compile(FuncCall("NOPE", listOf(n("1")))) }
            .hasMessageContaining("Unknown function: NOPE")
    }
}
