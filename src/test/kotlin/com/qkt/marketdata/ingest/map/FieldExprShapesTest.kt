package com.qkt.marketdata.ingest.map

import com.qkt.marketdata.ingest.InputLine
import com.qkt.marketdata.ingest.parse.NumberStyle
import com.qkt.marketdata.ingest.rows.CellRef
import com.qkt.marketdata.ingest.rows.ImportRow
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class FieldExprShapesTest {
    private val header = listOf("time", "bid", "ask")
    private val parser = FieldExprParser({ n -> header.indexOf(n).takeIf { it >= 0 }?.let { CellRef.Index(it) } })

    private fun row(vararg cells: String) =
        object : ImportRow {
            override val where = InputLine("t.csv", 2, "")

            override fun cell(ref: CellRef): String? = (ref as? CellRef.Index)?.let { cells.getOrNull(it.index) }
        }

    private fun eval(
        text: String,
        constants: SymbolConstants = SymbolConstants(null, null),
    ) = parser.parse(text).eval(row("0", "2", "4"), NumberStyle(), constants)

    @Test
    fun `numbers with a fraction or an exponent are constants`() {
        assertThat(eval("bid * 1.5")).isEqualByComparingTo("3")
        assertThat(eval("bid * 1e3")).isEqualByComparingTo("2000")
        assertThat(eval("bid * 1E2")).isEqualByComparingTo("200")
    }

    @Test
    fun `a quoted name and a bracketed position read their cells`() {
        assertThat(eval("'ask' - [1]")).isEqualByComparingTo("2")
        assertThat(eval("\"bid\" + [2]")).isEqualByComparingTo("6")
    }

    @Test
    fun `a bracketed position without its closing bracket is refused`() {
        assertThatThrownBy { parser.parse("[1 + bid") }.hasMessageContaining("missing ']'")
    }

    @Test
    fun `each expression lists the cells it reads`() {
        val expr = parser.parse("(bid + ask) / 2 * point + 1")

        assertThat(expr.cells).containsExactly(CellRef.Index(1), CellRef.Index(2))
        assertThat(FieldExpr.Missing.cells).isEmpty()
        assertThat(FieldExpr.Cell(CellRef.Index(0)).cells).containsExactly(CellRef.Index(0))
    }

    @Test
    fun `symbol constants evaluate from the instrument and refuse when it has none`() {
        val gold = SymbolConstants(BigDecimal("0.01"), 2)

        assertThat(eval("digits", gold)).isEqualByComparingTo("2")
        assertThat(eval("point", gold)).isEqualByComparingTo("0.01")
        assertThatThrownBy { eval("point") }.hasMessageContaining("'point'").hasMessageContaining("--digits")
        assertThatThrownBy { eval("digits") }.hasMessageContaining("'digits'").hasMessageContaining("--digits")
    }

    @Test
    fun `a missing optional column evaluates to nothing`() {
        assertThat(FieldExpr.Missing.eval(row(), NumberStyle(), SymbolConstants(null, null))).isNull()
    }
}
