package com.qkt.dsl.parse

import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.CrossDir
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.SnapshotBuy
import com.qkt.dsl.ast.SnapshotTPast
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserExprSnapshotTest {
    private fun expr(s: String): ExprAst {
        val r = Parser(Lexer("STRATEGY s VERSION 1\nLET x = $s").tokenize()).parseStrategy() as ParseResult.Success
        return r.value.lets[0].expr
    }

    @Test
    fun `parses snapshot at buy`() {
        val e = expr("fast@buy") as Ref
        assertThat(e.name).isEqualTo("fast")
        assertThat(e.snapshot).isEqualTo(SnapshotBuy)
    }

    @Test
    fun `parses snapshot at T past N`() {
        val e = expr("fast@T-3") as Ref
        assertThat(e.name).isEqualTo("fast")
        assertThat(e.snapshot).isEqualTo(SnapshotTPast(3))
    }

    @Test
    fun `parses MAX SINCE OPEN`() {
        val e = expr("MAX(s.high) SINCE OPEN") as Aggregate
        assertThat(e.fn).isEqualTo(AggFn.MAX)
        assertThat(e.window).isEqualTo(SinceOpen)
    }

    @Test
    fun `parses MEAN SINCE T past N`() {
        val e = expr("MEAN(s.close) SINCE T-5") as Aggregate
        assertThat(e.fn).isEqualTo(AggFn.MEAN)
        assertThat(e.window).isEqualTo(SinceTPast(5))
    }

    @Test
    fun `parses BETWEEN`() {
        val e = expr("a BETWEEN 1 AND 10") as Between
        assertThat((e.v as Ref).name).isEqualTo("a")
    }

    @Test
    fun `parses IN list`() {
        val e = expr("a IN [1, 2, 3]") as InList
        assertThat(e.members).hasSize(3)
    }

    @Test
    fun `parses CROSSES ABOVE`() {
        val e = expr("a CROSSES ABOVE b") as Crosses
        assertThat(e.direction).isEqualTo(CrossDir.ABOVE)
    }

    @Test
    fun `parses CROSSES BELOW`() {
        val e = expr("a CROSSES BELOW b") as Crosses
        assertThat(e.direction).isEqualTo(CrossDir.BELOW)
    }

    @Test
    fun `parses CASE WHEN ELSE END`() {
        val e = expr("CASE WHEN a > 0 THEN 1 ELSE 2 END") as CaseWhen
        assertThat(e.branches).hasSize(1)
    }
}
