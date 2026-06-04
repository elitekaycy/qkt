package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.StreamFieldRef
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprTransformTest {
    private val rewrite = ExprTransform { ref -> if (ref.name == "x") NumLit(BigDecimal("42")) else ref }

    @Test
    fun `the ref hook reaches refs nested in sizing and a bracket child price`() {
        val action =
            Buy(
                stream = "btc",
                opts =
                    ActionOpts(
                        sizing = SizeRiskFrac(Ref("x")),
                        bracket = BracketAst(takeProfit = ChildRr(Ref("x"))),
                    ),
            )

        val out = rewrite.action(action) as Buy

        assertThat((out.opts.sizing as SizeRiskFrac).frac).isEqualTo(NumLit(BigDecimal("42")))
        assertThat((out.opts.bracket!!.takeProfit as ChildRr).multiplier).isEqualTo(NumLit(BigDecimal("42")))
    }

    @Test
    fun `refs the hook does not match, and terminals, pass through unchanged`() {
        val sizing = SizeRiskFrac(Ref("y"))
        assertThat((rewrite.sizing(sizing) as SizeRiskFrac).frac).isEqualTo(Ref("y"))

        val terminal = StreamFieldRef("btc", "close")
        assertThat(rewrite.expr(terminal)).isEqualTo(terminal)
    }
}
