package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LexerStackTest {
    @Test
    fun `STACK SPACING WITHIN are recognized as keywords`() {
        val tokens = Lexer("STACK SPACING WITHIN").tokenize()
        assertThat(tokens.map { it.kind }).containsExactly(
            TokenKind.STACK,
            TokenKind.SPACING,
            TokenKind.WITHIN,
            TokenKind.EOF,
        )
    }
}
