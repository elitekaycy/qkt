package com.qkt.dsl.parse

import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StrategyAst
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserLetTest {
    private fun parse(s: String): StrategyAst =
        (Parser(Lexer(s).tokenize()).parseStrategy() as ParseResult.Success).value

    @Test
    fun `parses single let with numeric literal`() {
        val ast = parse("STRATEGY s VERSION 1\nLET threshold = 100")
        assertThat(ast.lets).hasSize(1)
        assertThat(ast.lets[0].name).isEqualTo("threshold")
        assertThat(ast.lets[0].expr).isEqualTo(NumLit(BigDecimal("100")))
    }

    @Test
    fun `parses multiple lets`() {
        val ast = parse("STRATEGY s VERSION 1\nLET a = 1, b = 2, c = 3")
        assertThat(ast.lets.map { it.name }).containsExactly("a", "b", "c")
    }
}
