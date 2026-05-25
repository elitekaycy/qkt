package com.qkt.dsl.parse

import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizeRiskFrac
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ParserSizingPctRiskTest {
    private fun parseSizing(input: String): com.qkt.dsl.ast.SizingAst {
        val tokens = Lexer(input).tokenize()
        val parser = Parser(tokens)
        return parser.parseSizing()
    }

    @Test
    fun `0_5 PCT RISK parses to SizeRiskFrac with 0_005`() {
        val s = parseSizing("0.5 PCT RISK")
        assertThat(s).isInstanceOf(SizeRiskFrac::class.java)
        val expr = (s as SizeRiskFrac).frac
        assertThat(expr).isInstanceOf(NumLit::class.java)
        assertThat((expr as NumLit).value).isEqualByComparingTo(BigDecimal("0.005"))
    }

    @Test
    fun `integer 1 PCT RISK parses to fraction 0_01`() {
        val s = parseSizing("1 PCT RISK") as SizeRiskFrac
        assertThat((s.frac as NumLit).value).isEqualByComparingTo(BigDecimal("0.01"))
    }

    @Test
    fun `decimal 0_25 PCT RISK parses to fraction 0_0025`() {
        val s = parseSizing("0.25 PCT RISK") as SizeRiskFrac
        assertThat((s.frac as NumLit).value).isEqualByComparingTo(BigDecimal("0.0025"))
    }

    @Test
    fun `0 PCT RISK is rejected at parse time`() {
        assertThatThrownBy { parseSizing("0 PCT RISK") }
            .hasMessageContaining("PCT RISK")
    }

    @Test
    fun `PCT without trailing RISK falls through to percent-of-equity sugar`() {
        // Existing surface: `0.5 % OF EQUITY`. We must not steal it.
        val s = parseSizing("0.5 % OF EQUITY")
        assertThat(s).isInstanceOf(SizePctEquity::class.java)
    }
}
