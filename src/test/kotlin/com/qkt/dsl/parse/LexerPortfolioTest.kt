package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LexerPortfolioTest {
    @Test
    fun `PORTFOLIO IMPORT AS RUN HOLD lex as keyword tokens`() {
        val tokens = Lexer("PORTFOLIO IMPORT AS RUN HOLD").tokenize()
        assertThat(tokens.map { it.kind }).containsExactly(
            TokenKind.PORTFOLIO,
            TokenKind.IMPORT,
            TokenKind.AS,
            TokenKind.RUN,
            TokenKind.HOLD,
            TokenKind.EOF,
        )
    }
}
