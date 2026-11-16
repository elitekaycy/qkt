package com.qkt.cli.requirements

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.BreakOffset
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.DirSense
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.LatchBracket
import com.qkt.dsl.ast.LatchEntry
import com.qkt.dsl.ast.LatchLimit
import com.qkt.dsl.ast.LatchMarket
import com.qkt.dsl.ast.LatchRetestHold
import com.qkt.dsl.ast.LatchStop
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.LogLevel
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Resize
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StreamFieldRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ActionExpressionVisitorTest {
    private val seen = mutableListOf<String>()

    private val visitor =
        ActionExpressionVisitor { expr: ExprAst? -> if (expr is StreamFieldRef) seen.add(expr.stream) }

    private fun r(name: String) = StreamFieldRef(name, "close")

    private fun sized(name: String) = ActionOpts(sizing = SizeQty(r(name)))

    @Test
    fun `a resize visits its target size and its minimum step`() {
        visitor.walkAction(Resize("s", SizeQty(r("target")), r("step")))
        visitor.walkAction(Resize("s", SizeQty(r("bare"))))

        assertThat(seen).containsExactly("target", "step", "bare")
    }

    @Test
    fun `an oco entry visits both legs`() {
        visitor.walkAction(OcoEntry(Buy("s", sized("long")), Sell("s", sized("short"))))

        assertThat(seen).containsExactly("long", "short")
    }

    @Test
    fun `a block visits each action in order and a log visits its fields`() {
        visitor.walkAction(
            Block(
                listOf(
                    Buy("s", sized("first")),
                    Log(LogLevel.INFO, "x={x}", mapOf("x" to r("field"))),
                    Sell("s", sized("last")),
                ),
            ),
        )

        assertThat(seen).containsExactly("first", "field", "last")
    }

    @Test
    fun `fill children and exit hooks are visited after the order's own expressions`() {
        val hooks = ActionOpts().exitHooks.copy(onStop = listOf(Buy("s", sized("stop"))))
        val opts = sized("own").copy(onFill = listOf(Sell("s", sized("fill"))), exitHooks = hooks)

        visitor.walkAction(Buy("s", opts))

        assertThat(seen).containsExactly("own", "fill", "stop")
    }

    @Test
    fun `a latch visits its sensor, retest distance, entry prices, brackets and sizes`() {
        val latch =
            Latch(
                stream = "s",
                sensor = BreakOffset(r("reference"), r("offset")),
                armWindow = DurationAst(60_000L),
                name = null,
                entries =
                    listOf(
                        LatchEntry(
                            LatchLimit(DirRel(DirSense.AGAINST, r("limit"))),
                            LatchBracket(DirRel(DirSense.AGAINST, r("sl")), DirRel(DirSense.WITH, r("tp"))),
                            SizeQty(r("size")),
                        ),
                        LatchEntry(LatchStop(DirRel(DirSense.WITH, r("stop"))), LatchBracket()),
                        LatchEntry(LatchMarket),
                    ),
                confirm = LatchRetestHold(r("retest"), DurationAst(1_000L)),
            )

        visitor.walkAction(latch)

        assertThat(seen).containsExactly("reference", "offset", "retest", "limit", "sl", "tp", "size", "stop")
    }

    @Test
    fun `a first-tick latch has no confirmation expression`() {
        val latch =
            Latch("s", BreakOffset(null, r("offset")), DurationAst(60_000L), "wire", listOf(LatchEntry(LatchMarket)))

        visitor.walkAction(latch)

        assertThat(seen).containsExactly("offset")
    }

    @Test
    fun `closing and cancelling actions evaluate nothing`() {
        listOf(Close("s"), CloseAll, Cancel("s"), CancelAll).forEach(visitor::walkAction)

        assertThat(seen).isEmpty()
    }
}
