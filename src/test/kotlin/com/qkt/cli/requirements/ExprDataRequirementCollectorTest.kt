package com.qkt.cli.requirements

import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.CrossDir
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprDataRequirementCollectorTest {
    private fun f(
        stream: String,
        field: String = "close",
    ) = StreamFieldRef(stream, field)

    private val one = NumLit(BigDecimal.ONE)

    /** Every compound expression shape, with [leaf] in each child position it can hold. */
    private fun everyShape(leaf: (String) -> ExprAst): List<ExprAst> =
        listOf(
            BinaryOp(BinOp.ADD, leaf("a"), leaf("b")),
            UnaryOp(UnOp.NEG, leaf("c")),
            CmpOp(Cmp.GT, leaf("d"), leaf("e")),
            Crosses(CrossDir.ABOVE, leaf("f"), leaf("g")),
            FuncCall("abs", listOf(leaf("h"))),
            IndicatorCall("EMA", listOf(leaf("i"), one)),
            Aggregate(AggFn.MAX, leaf("j"), SinceOpen),
            Between(leaf("k"), leaf("l"), leaf("m")),
            InList(leaf("n"), listOf(leaf("o"))),
            CaseWhen(listOf(leaf("p") to leaf("q")), leaf("r")),
            IsNull(leaf("s"), negated = false),
        )

    @Test
    fun `quote reads are found in every position of every expression shape`() {
        val collector = ExprDataRequirementCollector()

        everyShape { f(it, "bid") }.forEach(collector::walk)

        assertThat(collector.quoteAliases).containsExactlyElementsOf(('a'..'s').map { it.toString() })
        assertThat(collector.volumeAliases).isEmpty()
    }

    @Test
    fun `a volume indicator over a compound source reads volume from every alias inside it`() {
        everyShape(::f).forEach { source ->
            val collector = ExprDataRequirementCollector()

            collector.walk(IndicatorCall("OBV", listOf(source)))

            assertThat(collector.volumeAliases).withFailMessage("$source").isNotEmpty()
        }
    }

    @Test
    fun `a volume indicator whose first argument names no stream reads every argument's streams`() {
        val collector = ExprDataRequirementCollector()

        collector.walk(IndicatorCall("VWAP", listOf(Ref("x"), f("gold"))))

        assertThat(collector.volumeAliases).containsExactly("gold")
    }

    @Test
    fun `collectAliases ignores leaves that are not stream fields`() {
        val out = mutableSetOf<String>()

        ExprDataRequirementCollector().collectAliases(BinaryOp(BinOp.MUL, one, Ref("x")), out)

        assertThat(out).isEmpty()
    }
}
