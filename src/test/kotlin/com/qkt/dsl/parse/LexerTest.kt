package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LexerTest {
    @Test
    fun `tokenizes case-insensitive keywords`() {
        val tokens = Lexer("STRATEGY strategy Strategy").tokenize()
        assertThat(tokens.map { it.kind })
            .containsExactly(TokenKind.STRATEGY, TokenKind.STRATEGY, TokenKind.STRATEGY, TokenKind.EOF)
    }

    @Test
    fun `case-sensitive identifiers preserve original casing`() {
        val tokens = Lexer("btc BTC mySymbol").tokenize()
        assertThat(tokens.dropLast(1).map { it.lexeme }).containsExactly("btc", "BTC", "mySymbol")
        assertThat(tokens.dropLast(1).map { it.kind }).allMatch { it == TokenKind.IDENT }
    }

    @Test
    fun `identifier with underscore and digits`() {
        val tokens = Lexer("btc_h1 my_var2").tokenize()
        assertThat(tokens.dropLast(1).map { it.lexeme }).containsExactly("btc_h1", "my_var2")
    }

    @Test
    fun `tracks line and column across newlines`() {
        val tokens = Lexer("STRATEGY\n  btc").tokenize()
        assertThat(tokens[1].line).isEqualTo(2)
        assertThat(tokens[1].col).isEqualTo(3)
    }

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
    fun `unterminated string errors`() {
        assertThatThrownBy { Lexer("'oops").tokenize() }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `tokenizes comparison operators with longest-match`() {
        val tokens = Lexer("> < >= <= == != =").tokenize()
        assertThat(tokens.dropLast(1).map { it.kind }).containsExactly(
            TokenKind.GT,
            TokenKind.LT,
            TokenKind.GE,
            TokenKind.LE,
            TokenKind.EQEQ,
            TokenKind.NEQ,
            TokenKind.EQ,
        )
    }

    @Test
    fun `tokenizes arithmetic operators`() {
        val tokens = Lexer("+ - * / %").tokenize()
        assertThat(tokens.dropLast(1).map { it.kind }).containsExactly(
            TokenKind.PLUS,
            TokenKind.MINUS,
            TokenKind.STAR,
            TokenKind.SLASH,
            TokenKind.PERCENT,
        )
    }

    @Test
    fun `tokenizes punctuation`() {
        val tokens = Lexer("{ } [ ] ( ) , . ; : @ \$").tokenize()
        assertThat(tokens.dropLast(1).map { it.kind }).containsExactly(
            TokenKind.LBRACE,
            TokenKind.RBRACE,
            TokenKind.LBRACKET,
            TokenKind.RBRACKET,
            TokenKind.LPAREN,
            TokenKind.RPAREN,
            TokenKind.COMMA,
            TokenKind.DOT,
            TokenKind.SEMICOLON,
            TokenKind.COLON,
            TokenKind.AT_SIGN,
            TokenKind.DOLLAR,
        )
    }

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
    fun `recognizes broker-symbol colon syntax`() {
        val tokens = Lexer("BYBIT:BTCUSDT").tokenize()
        assertThat(tokens.map { it.kind }).containsExactly(
            TokenKind.IDENT,
            TokenKind.COLON,
            TokenKind.IDENT,
            TokenKind.EOF,
        )
    }
}
