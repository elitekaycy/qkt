package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LexerLiteralTest {
    @Test
    fun `tokenizes integers and decimals`() {
        val tokens = Lexer("100 100.5 0.001").tokenize()
        assertThat(tokens.dropLast(1).map { it.lexeme }).containsExactly("100", "100.5", "0.001")
        tokens.dropLast(1).forEach { assertThat(it.kind).isEqualTo(TokenKind.NUMBER) }
    }

    @Test
    fun `tokenizes scientific notation`() {
        val tokens = Lexer("1e-3 2.5E+10 1.5e6").tokenize()
        assertThat(tokens.dropLast(1).map { it.lexeme }).containsExactly("1e-3", "2.5E+10", "1.5e6")
    }

    @Test
    fun `tokenizes single-quoted strings`() {
        val tokens = Lexer("'hello world'").tokenize()
        assertThat(tokens[0].kind).isEqualTo(TokenKind.STRING)
        assertThat(tokens[0].lexeme).isEqualTo("hello world")
    }

    @Test
    fun `string escape sequences`() {
        val tokens = Lexer("""'don\'t \\fire'""").tokenize()
        assertThat(tokens[0].lexeme).isEqualTo("""don't \fire""")
    }

    @Test
    fun `tokenizes double-quoted strings`() {
        val tokens = Lexer("\"hello world\"").tokenize()
        assertThat(tokens[0].kind).isEqualTo(TokenKind.STRING)
        assertThat(tokens[0].lexeme).isEqualTo("hello world")
    }

    @Test
    fun `double-quoted strings support escapes`() {
        val tokens = Lexer(""""she said \"hi\" and \\smiled"""").tokenize()
        assertThat(tokens[0].lexeme).isEqualTo("""she said "hi" and \smiled""")
    }

    @Test
    fun `single quotes pass through double-quoted strings unescaped`() {
        val tokens = Lexer(""""don't fire"""").tokenize()
        assertThat(tokens[0].lexeme).isEqualTo("don't fire")
    }

    @Test
    fun `unterminated string errors`() {
        assertThatThrownBy { Lexer("'oops").tokenize() }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `unterminated double-quoted string errors`() {
        assertThatThrownBy { Lexer("\"oops").tokenize() }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
