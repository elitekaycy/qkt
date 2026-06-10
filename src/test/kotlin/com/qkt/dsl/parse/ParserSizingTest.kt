package com.qkt.dsl.parse

import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizingAst
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserSizingTest {
    private fun parseSizing(s: String): SizingAst = Parser(Lexer(s).tokenize()).parseSizing()

    @Test
    fun `qty with no suffix`() {
        val r = parseSizing("1.5") as SizeQty
        assertThat((r.expr as NumLit).value).isEqualTo(BigDecimal("1.5"))
    }

    @Test
    fun `USD notional`() {
        val r = parseSizing("100 USD")
        assertThat(r).isInstanceOf(SizeNotional::class.java)
    }

    @Test
    fun `pct equity normalizes percent to fraction`() {
        val r = parseSizing("5 % OF EQUITY") as SizePctEquity
        assertThat((r.frac as NumLit).value).isEqualByComparingTo(BigDecimal("0.05"))
    }

    @Test
    fun `pct balance normalizes percent to fraction`() {
        val r = parseSizing("0.5 % OF BALANCE") as SizePctBalance
        assertThat((r.frac as NumLit).value).isEqualByComparingTo(BigDecimal("0.005"))
    }

    @Test
    fun `PCT OF EQUITY is the documented spelling of percent-of-equity`() {
        val r = parseSizing("5 PCT OF EQUITY") as SizePctEquity
        assertThat((r.frac as NumLit).value).isEqualByComparingTo(BigDecimal("0.05"))
    }

    @Test
    fun `risk frac`() {
        val r = parseSizing("RISK 0.01")
        assertThat(r).isInstanceOf(SizeRiskFrac::class.java)
    }

    @Test
    fun `risk abs dollar`() {
        val r = parseSizing("RISK \$ 100")
        assertThat(r).isInstanceOf(SizeRiskAbs::class.java)
    }

    @Test
    fun `position full`() {
        val r = parseSizing("POSITION.btc") as SizePositionFull
        assertThat(r.stream).isEqualTo("btc")
    }
}
