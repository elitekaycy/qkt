package com.qkt.dsl.parse

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BreakOffset
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.DirSense
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.LatchEntry
import com.qkt.dsl.ast.LatchLimit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackSpacing
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.WhenThen
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IterVarSubstitutionTest {
    private fun trivialCond(stream: String) = CmpOp(Cmp.GT, StreamFieldRef(stream, "close"), NumLit(BigDecimal("0")))

    @Test
    fun `FOR EACH keeps a STACK clause and substitutes the iter var inside it`() {
        val rule =
            WhenThen(
                cond = trivialCond("s"),
                action =
                    Buy(
                        "s",
                        ActionOpts(
                            stack =
                                StackSpacing(
                                    count = 2,
                                    spacing = StreamFieldRef("s", "close"),
                                    direction = StackDirection.TRADE_DIRECTION,
                                ),
                        ),
                    ),
            )
        val out = substituteIterVar(rule, iterVar = "s", alias = "gold")
        val stack = (out.action as Buy).opts.stack as StackSpacing
        assertThat(stack.count).isEqualTo(2)
        assertThat(stack.spacing).isEqualTo(StreamFieldRef("gold", "close"))
    }

    @Test
    fun `FOR EACH substitutes the iter var inside a latch sensor and entry`() {
        val rule =
            WhenThen(
                cond = trivialCond("s"),
                action =
                    Latch(
                        stream = "s",
                        sensor = BreakOffset(reference = StreamFieldRef("s", "high"), offset = NumLit(BigDecimal("5"))),
                        armWindow = DurationAst(60_000L),
                        name = null,
                        entries =
                            listOf(
                                LatchEntry(order = LatchLimit(DirRel(DirSense.AGAINST, StreamFieldRef("s", "low")))),
                            ),
                    ),
            )
        val out = substituteIterVar(rule, iterVar = "s", alias = "gold")
        val latch = out.action as Latch
        assertThat(latch.stream).isEqualTo("gold")
        assertThat((latch.sensor as BreakOffset).reference).isEqualTo(StreamFieldRef("gold", "high"))
        val entryPrice = (latch.entries.single().order as LatchLimit).price
        assertThat(entryPrice.dist).isEqualTo(StreamFieldRef("gold", "low"))
    }
}
