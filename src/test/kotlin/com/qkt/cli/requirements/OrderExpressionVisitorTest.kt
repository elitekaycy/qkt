package com.qkt.cli.requirements

import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackAtClause
import com.qkt.dsl.ast.StackLayer
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.StreamFieldRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderExpressionVisitorTest {
    private val seen = mutableListOf<String>()

    private val visitor =
        OrderExpressionVisitor { expr: ExprAst? -> if (expr is StreamFieldRef) seen.add(expr.stream) }

    private fun r(name: String) = StreamFieldRef(name, "close")

    @Test
    fun `a stop limit visits both prices`() {
        visitor.walkOrder(StopLimit(r("stop"), r("limit")))

        assertThat(seen).containsExactly("stop", "limit")
    }

    @Test
    fun `an oco visits both legs`() {
        visitor.walkOco(OcoAst(ChildAt(r("sl")), ChildAt(r("tp"))))
        visitor.walkOco(null)

        assertThat(seen).containsExactly("sl", "tp")
    }

    @Test
    fun `a stack layer visits its sizing order and trigger`() {
        visitor.walkStack(StackLayers(listOf(StackLayer(SizeQty(r("size")), Limit(r("price")), r("at")))))

        assertThat(seen).containsExactly("size", "price", "at")
    }

    @Test
    fun `a stack-at clause visits thresholds sizing and bracket`() {
        visitor.walkStackAt(
            StackAtClause(
                mfeThreshold = r("mfe"),
                withinDuration = DurationAst(60_000L),
                sizing = SizeQty(r("size")),
                bracket = BracketAst(ChildAt(r("sl")), ChildAt(r("tp"))),
                maeRecoverDistance = r("mae"),
            ),
        )

        assertThat(seen).containsExactly("mfe", "mae", "size", "sl", "tp")
    }
}
