package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.Ioc
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackAtClause
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackSpacing
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** v1 keeps an ON_FILL parent and its children plain: every extra clause is refused at compile time. */
class OtoShapeRefusalTest {
    private val one = NumLit(BigDecimal.ONE)
    private val size = SizeQty(one)
    private val child = Sell("b", ActionOpts(sizing = size))
    private val bracket = BracketAst(ChildBy(one), ChildBy(one))
    private val stackAt = StackAtClause(one, DurationAst(60_000L), size, bracket)

    private fun compile(action: ActionAst) =
        ActionCompiler(ExprCompiler(), baskets = mapOf("pair" to listOf("b", "c"))).compile(action)

    private fun parent(opts: ActionOpts) = Buy("b", opts.copy(onFill = listOf(child)))

    private fun withChild(c: ActionAst) = Buy("b", ActionOpts(sizing = size, onFill = listOf(c)))

    @Test
    fun `an ON_FILL parent carries no OCO, STACK_AT or GTD and needs a size`() {
        assertThatThrownBy { compile(parent(ActionOpts(sizing = size, oco = OcoAst(ChildAt(one), ChildAt(one))))) }
            .hasMessageContaining("cannot also carry an OCO in v1.")
        assertThatThrownBy { compile(parent(ActionOpts(sizing = size, stackAts = listOf(stackAt)))) }
            .hasMessageContaining("cannot also carry STACK/STACK_AT in v1.")
        assertThatThrownBy { compile(parent(ActionOpts(sizing = size, tif = Gtd(one)))) }
            .hasMessageContaining("does not support TIF GTD in v1.")
        assertThatThrownBy { compile(parent(ActionOpts())) }.hasMessageContaining("OTO parent BUY/SELL requires SIZING")
    }

    @Test
    fun `an ON_FILL child carries no nested ON_FILL, OCO, STACK_AT, TIF or basket and needs a size`() {
        val oco = OcoAst(ChildAt(one), ChildAt(one))
        val stack = StackSpacing(2, one, StackDirection.ABOVE)

        assertThatThrownBy { compile(withChild(Buy("b", ActionOpts(sizing = size, onFill = listOf(child))))) }
            .hasMessageContaining("cannot nest their own ON_FILL")
        assertThatThrownBy { compile(withChild(Sell("b", ActionOpts(sizing = size, oco = oco)))) }
            .hasMessageContaining("cannot carry a BRACKET or OCO in v1.")
        assertThatThrownBy { compile(withChild(Sell("b", ActionOpts(sizing = size, stackAts = listOf(stackAt))))) }
            .hasMessageContaining("cannot carry STACK/STACK_AT in v1.")
        assertThatThrownBy { compile(withChild(Sell("b", ActionOpts(sizing = size, tif = Ioc)))) }
            .hasMessageContaining("cannot carry a TIF in v1.")
        assertThatThrownBy { compile(withChild(Sell("pair", ActionOpts(sizing = size)))) }
            .hasMessageContaining("cannot target a BASKET in v1.")
        assertThatThrownBy {
            compile(withChild(Sell("b", ActionOpts())))
        }.hasMessageContaining("ON_FILL child BUY/SELL requires SIZING")
        assertThatThrownBy { compile(withChild(Sell("b", ActionOpts(sizing = size, stack = stack)))) }
            .hasMessageContaining("cannot carry STACK/STACK_AT in v1.")
    }
}
