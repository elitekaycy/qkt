package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LexerTest {
    @Test
    fun `tokenizes case-insensitive keywords`() {
        val tokens = Lexer("STRATEGY strategy Strategy").tokenize()
        assertThat(tokens.map { it.kind })
            .containsExactly(TokenKind.STRATEGY, TokenKind.STRATEGY, TokenKind.STRATEGY, TokenKind.EOF)
    }

    @Test
    fun `AFTER is tokenized as TokenKind AFTER`() {
        val tokens = Lexer("AFTER after After").tokenize()
        assertThat(tokens.map { it.kind })
            .containsExactly(TokenKind.AFTER, TokenKind.AFTER, TokenKind.AFTER, TokenKind.EOF)
    }

    @Test
    fun `SYNCHRONIZE is tokenized as TokenKind SYNCHRONIZE`() {
        val tokens = Lexer("SYNCHRONIZE synchronize Synchronize").tokenize()
        assertThat(tokens.map { it.kind })
            .containsExactly(
                TokenKind.SYNCHRONIZE,
                TokenKind.SYNCHRONIZE,
                TokenKind.SYNCHRONIZE,
                TokenKind.EOF,
            )
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
    fun `tokenizes REGIMES keyword case-insensitively`() {
        val tokens = Lexer("REGIMES regimes Regimes").tokenize()
        assertThat(tokens.map { it.kind })
            .containsExactly(TokenKind.REGIMES, TokenKind.REGIMES, TokenKind.REGIMES, TokenKind.EOF)
    }

    @Test
    fun `NOW and OCO_ENTRY are case-insensitive keywords`() {
        val tokens = Lexer("NOW now Now OCO_ENTRY oco_entry").tokenize()
        assertThat(tokens.dropLast(1).map { it.kind }).containsExactly(
            TokenKind.NOW,
            TokenKind.NOW,
            TokenKind.NOW,
            TokenKind.OCO_ENTRY,
            TokenKind.OCO_ENTRY,
        )
    }

    @Test
    fun `tokenizes phase 24 keywords`() {
        val tokens = Lexer("WARMUP BARS FLATTEN IS NULL").tokenize()
        val kinds = tokens.map { it.kind }
        assertThat(kinds).containsExactly(
            TokenKind.WARMUP,
            TokenKind.BARS,
            TokenKind.FLATTEN,
            TokenKind.IS,
            TokenKind.NULL,
            TokenKind.EOF,
        )
    }

    @Test
    fun `phase 24 keywords are case-insensitive`() {
        val tokens = Lexer("warmup bars flatten is null").tokenize()
        assertThat(tokens.map { it.kind }).containsExactly(
            TokenKind.WARMUP,
            TokenKind.BARS,
            TokenKind.FLATTEN,
            TokenKind.IS,
            TokenKind.NULL,
            TokenKind.EOF,
        )
    }

    @Test
    fun `SCHEDULE HOUR WEEKDAY UTC are tokenized as their TokenKind variants`() {
        val tokens = Lexer("SCHEDULE HOUR WEEKDAY UTC").tokenize()
        assertThat(tokens.map { it.kind }).startsWith(
            TokenKind.SCHEDULE,
            TokenKind.HOUR,
            TokenKind.WEEKDAY,
            TokenKind.UTC,
        )
    }
}
