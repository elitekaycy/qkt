package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserHeaderTest {
    private fun parse(s: String): ParseResult<com.qkt.dsl.ast.StrategyAst> = Parser(Lexer(s).tokenize()).parseStrategy()

    @Test
    fun `parses STRATEGY name VERSION n`() {
        val r = parse("STRATEGY momentum_basket VERSION 1") as ParseResult.Success
        assertThat(r.value.name).isEqualTo("momentum_basket")
        assertThat(r.value.version).isEqualTo(1)
    }

    @Test
    fun `header without version errors`() {
        val r = parse("STRATEGY x") as ParseResult.Failure
        assertThat(r.errors).isNotEmpty
    }

    @Test
    fun `header with non-integer version errors`() {
        val r = parse("STRATEGY x VERSION abc") as ParseResult.Failure
        assertThat(r.errors).isNotEmpty
    }
}
