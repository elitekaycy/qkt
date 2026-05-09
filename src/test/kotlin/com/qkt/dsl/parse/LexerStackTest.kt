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

    @Test
    fun `1h lexes as DURATION token`() {
        val tokens = Lexer("1h").tokenize()
        assertThat(tokens.map { it.kind to it.lexeme }).containsExactly(
            TokenKind.DURATION to "1h",
            TokenKind.EOF to "",
        )
    }

    @Test
    fun `30m 15s 2d all lex as DURATION`() {
        for (lit in listOf("30m", "15s", "2d", "120s")) {
            val tokens = Lexer(lit).tokenize()
            assertThat(tokens[0].kind).`as`("$lit token kind").isEqualTo(TokenKind.DURATION)
            assertThat(tokens[0].lexeme).isEqualTo(lit)
        }
    }

    @Test
    fun `digits without duration suffix lex as NUMBER`() {
        val tokens = Lexer("100").tokenize()
        assertThat(tokens[0].kind).isEqualTo(TokenKind.NUMBER)
        assertThat(tokens[0].lexeme).isEqualTo("100")
    }

    @Test
    fun `decimal followed by h does not lex as DURATION`() {
        // 1.5h must lex as NUMBER 1.5 then IDENT h, not DURATION
        val tokens = Lexer("1.5h").tokenize()
        assertThat(tokens.map { it.kind }).containsExactly(
            TokenKind.NUMBER,
            TokenKind.IDENT,
            TokenKind.EOF,
        )
        assertThat(tokens[0].lexeme).isEqualTo("1.5")
        assertThat(tokens[1].lexeme).isEqualTo("h")
    }

    @Test
    fun `uppercase suffix is not a DURATION`() {
        val tokens = Lexer("1H").tokenize()
        assertThat(tokens.map { it.kind }).containsExactly(
            TokenKind.NUMBER,
            TokenKind.IDENT,
            TokenKind.EOF,
        )
    }
}
