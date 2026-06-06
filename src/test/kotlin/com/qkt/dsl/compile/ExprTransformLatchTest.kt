package com.qkt.dsl.compile

import com.qkt.dsl.ast.BreakOffset
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.DirSense
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.LatchBracket
import com.qkt.dsl.ast.LatchEntry
import com.qkt.dsl.ast.LatchLimit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprTransformLatchTest {
    @Test
    fun `transform resolves refs inside latch offset, distances, and bracket`() {
        // onRef stands in for LET resolution: near -> 4, sl -> 12, tp -> 5, off -> 0.50.
        val table = mapOf("near" to "4", "sl" to "12", "tp" to "5", "off" to "0.50")
        val transform = ExprTransform { ref -> table[ref.name]?.let { NumLit(BigDecimal(it)) } ?: ref }

        val latch =
            Latch(
                stream = "gold",
                sensor = BreakOffset(reference = null, offset = Ref("off")),
                armWindow = DurationAst(300_000L),
                name = null,
                entries =
                    listOf(
                        LatchEntry(
                            order = LatchLimit(DirRel(DirSense.AGAINST, Ref("near"))),
                            bracket =
                                LatchBracket(
                                    stopLoss = DirRel(DirSense.AGAINST, Ref("sl")),
                                    takeProfit = DirRel(DirSense.WITH, Ref("tp")),
                                ),
                        ),
                    ),
            )

        val out = transform.action(latch) as Latch
        assertThat((out.sensor as BreakOffset).offset).isEqualTo(NumLit(BigDecimal("0.50")))
        val entry = out.entries.single()
        assertThat((entry.order as LatchLimit).price.dist).isEqualTo(NumLit(BigDecimal("4")))
        assertThat(entry.bracket!!.stopLoss!!.dist).isEqualTo(NumLit(BigDecimal("12")))
        assertThat(entry.bracket!!.takeProfit!!.dist).isEqualTo(NumLit(BigDecimal("5")))
    }

    @Test
    fun `LetResolver inlines LET refs inside a parsed latch action`() {
        // Guards the action-resolution wiring: LET-named latch distances must become
        // literals before compilation, otherwise LatchCompiler rejects them at fire time.
        val ast =
            (
                Dsl.parse(
                    """
                    STRATEGY t VERSION 1
                    SYMBOLS
                        gold = BACKTEST:XAUUSD EVERY 5m
                    LET wire = 0.50, near = 4, sl = 12, tp = 5
                    RULES
                        WHEN POSITION.gold = 0
                        THEN LATCH gold OFFSET wire ARM 5m {
                            ENTER LIMIT RETRACE near BRACKET { STOP LOSS AGAINST sl, TAKE PROFIT WITH tp } SIZING 1
                        }
                    """.trimIndent(),
                ) as ParseResult.Success
            ).value
        val action = (ast.rules.single() as WhenThen).action
        val resolved = LetResolver(ast.lets).resolve(action) as Latch

        assertThat((resolved.sensor as BreakOffset).offset).isEqualTo(NumLit(BigDecimal("0.50")))
        val entry = resolved.entries.single()
        assertThat((entry.order as LatchLimit).price.dist).isEqualTo(NumLit(BigDecimal("4")))
        assertThat(entry.bracket!!.stopLoss!!.dist).isEqualTo(NumLit(BigDecimal("12")))
        assertThat(entry.bracket!!.takeProfit!!.dist).isEqualTo(NumLit(BigDecimal("5")))
    }
}
