package com.qkt.marketdata.ingest.map

import com.qkt.marketdata.ingest.InputLine
import com.qkt.marketdata.ingest.parse.NumberStyle
import com.qkt.marketdata.ingest.rows.CellRef
import com.qkt.marketdata.ingest.rows.ImportRow
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class FieldExprParserTest {
    private val header = listOf("Gmt time", "<CLOSE>", "<SPREAD>", "Bid", "Ask")
    private val parser =
        FieldExprParser({ n -> header.indexOf(n).takeIf { it >= 0 }?.let { CellRef.Index(it) } }) { header }
    private val gold = SymbolConstants(BigDecimal("0.001"), 3)

    private fun row(vararg cells: String?) =
        object : ImportRow {
            override val where = InputLine("t.csv", 2, "")

            override fun cell(ref: CellRef): String? = (ref as? CellRef.Index)?.let { cells.getOrNull(it.index) }
        }

    private fun eval(
        text: String,
        r: ImportRow,
    ) = parser.parse(text).eval(r, NumberStyle(), gold)

    @Test
    fun `a whole text that is a column name needs no quoting, spaces included`() {
        assertThat(eval("Gmt time", row("7"))).isEqualTo(BigDecimal("7"))
    }

    @Test
    fun `MetaTrader bid to mid is exact decimal arithmetic`() {
        val r = row("", "2180.877", "160")

        assertThat(eval("<CLOSE> + <SPREAD> * point / 2", r)).isEqualByComparingTo("2180.957")
    }

    @Test
    fun `positions, constants, parentheses and unary minus`() {
        val r = row("", "", "", "1.10000", "1.10020")

        assertThat(eval("($3 + $4) / 2", r)).isEqualByComparingTo("1.10010")
        assertThat(eval("-Bid * 1000000", r)).isEqualByComparingTo("-1100000")
    }

    @Test
    fun `an absent cell makes the whole value absent, never a partial sum`() {
        assertThat(eval("Bid + <SPREAD> * point", row("", "", null, "1.1"))).isNull()
    }

    @Test
    fun `an unknown column names the columns there are`() {
        assertThatThrownBy {
            parser.parse(
                "Close",
            )
        }.hasMessageContaining("no column 'Close'").hasMessageContaining("<CLOSE>")
    }

    @Test
    fun `division by zero is a row error, not an infinity`() {
        assertThatThrownBy { eval("Bid / 0", row("", "", "", "1.1")) }.isInstanceOf(NumberFormatException::class.java)
    }
}
