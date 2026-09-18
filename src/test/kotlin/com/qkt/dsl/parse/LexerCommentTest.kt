package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LexerCommentTest {
    @Test
    fun `skips line comments`() {
        val tokens = Lexer("STRATEGY -- this is a comment\n  btc").tokenize()
        assertThat(tokens.map { it.kind }).containsExactly(
            TokenKind.STRATEGY,
            TokenKind.IDENT,
            TokenKind.EOF,
        )
    }

    @Test
    fun `skips block comments`() {
        val tokens = Lexer("STRATEGY /* this is a\n block comment */ btc").tokenize()
        assertThat(tokens.map { it.kind }).containsExactly(
            TokenKind.STRATEGY,
            TokenKind.IDENT,
            TokenKind.EOF,
        )
    }

    @Test
    fun `rejects unterminated block comments`() {
        assertThatThrownBy { Lexer("STRATEGY\n  /* never closed").tokenize() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Unterminated block comment at line 2 col 3")
    }

    @Test
    fun `hash line comments are skipped`() {
        val src =
            """
            # opening note
            STRATEGY hi VERSION 1
            # mid comment
            SYMBOLS
                btc = X:Y EVERY 1m
            """.trimIndent()
        val kinds = Lexer(src).tokenize().map { it.kind }
        assertThat(kinds).contains(TokenKind.STRATEGY, TokenKind.SYMBOLS, TokenKind.EVERY)
    }

    @Test
    fun `mixed comment styles all work`() {
        val src =
            """
            -- dash comment
            # hash comment
            /* block
               comment */
            STRATEGY x VERSION 1
            """.trimIndent()
        val kinds = Lexer(src).tokenize().map { it.kind }
        assertThat(kinds).containsSequence(TokenKind.STRATEGY, TokenKind.IDENT, TokenKind.VERSION)
    }
}
