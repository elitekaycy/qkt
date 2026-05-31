package com.qkt.dsl.parse

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.NumLit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * #48 — STOP LOSS TRAILING <distance> AFTER MFE >= <threshold> as a bracket leg.
 *
 * Parser-level coverage only: AST shape, TP-rejection at parse time, helpful errors
 * on missing AFTER/MFE/>= tokens. Semantic and engine behaviour are covered
 * downstream (ChildPriceResolverArmedTrailTest, OrderManagerArmedTrailTest).
 */
class ParserArmedTrailingStopTest {
    @Test
    fun `STOP LOSS TRAILING distance AFTER MFE GE threshold parses to ChildArmedTrail`() {
        val src = "BUY btc SIZING 0.1 BRACKET { STOP LOSS TRAILING 5 AFTER MFE >= 10, TAKE PROFIT BY 50 }"
        val r = Parser(Lexer(src).tokenize()).parseAction() as Buy
        val sl = r.opts.bracket?.stopLoss
        assertThat(sl).isInstanceOf(ChildArmedTrail::class.java)
        val armed = sl as ChildArmedTrail
        assertThat((armed.trailDistance as NumLit).value).isEqualByComparingTo("5")
        assertThat((armed.mfeThreshold as NumLit).value).isEqualByComparingTo("10")
    }

    @Test
    fun `TAKE PROFIT TRAILING is rejected at parse time`() {
        val src = "BUY btc SIZING 0.1 BRACKET { STOP LOSS AT 100, TAKE PROFIT TRAILING 5 AFTER MFE >= 10 }"
        assertThatThrownBy { Parser(Lexer(src).tokenize()).parseAction() }
            .hasMessageContainingAll("TAKE PROFIT", "TRAILING")
    }

    @Test
    fun `missing AFTER token after TRAILING distance is rejected`() {
        val src = "BUY btc SIZING 0.1 BRACKET { STOP LOSS TRAILING 5 MFE >= 10, TAKE PROFIT BY 50 }"
        assertThatThrownBy { Parser(Lexer(src).tokenize()).parseAction() }
            .hasMessageContaining("AFTER")
    }

    @Test
    fun `missing MFE token after AFTER is rejected`() {
        val src = "BUY btc SIZING 0.1 BRACKET { STOP LOSS TRAILING 5 AFTER 10, TAKE PROFIT BY 50 }"
        assertThatThrownBy { Parser(Lexer(src).tokenize()).parseAction() }
            .hasMessageContaining("MFE")
    }
}
