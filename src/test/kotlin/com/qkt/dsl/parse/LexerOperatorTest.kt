package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LexerOperatorTest {
    @Test
    fun `tokenizes both not-equal spellings`() {
        // conditions.md documents `!=` and `<>` as equivalent; both must lex to NEQ.
        val tokens = Lexer("a != b <> c").tokenize()
        assertThat(tokens.map { it.kind }.filter { it == TokenKind.NEQ }).hasSize(2)
    }

    @Test
    fun `tokenizes arrow operator`() {
        val tokens = Lexer("a -> b").tokenize()
        assertThat(tokens.map { it.kind })
            .containsExactly(TokenKind.IDENT, TokenKind.ARROW, TokenKind.IDENT, TokenKind.EOF)
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
