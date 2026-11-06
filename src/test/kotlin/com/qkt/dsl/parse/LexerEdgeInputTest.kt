package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Lexer edge inputs: comments at end of file, whitespace kinds, string escapes and malformed literals. */
class LexerEdgeInputTest {
    private fun kinds(src: String) = Lexer(src).tokenize().map { it.kind }

    private fun lexemes(src: String) = Lexer(src).tokenize().filter { it.kind != TokenKind.EOF }.map { it.lexeme }

    @Test
    fun `comments to end of file and tab or CR whitespace are skipped but an open block comment is not`() {
        assertThat(kinds("x -- trailing")).containsExactly(TokenKind.IDENT, TokenKind.EOF)
        assertThat(kinds("x # trailing")).containsExactly(TokenKind.IDENT, TokenKind.EOF)
        assertThat(kinds("x\t\r\ny /* closed */")).containsExactly(TokenKind.IDENT, TokenKind.IDENT, TokenKind.EOF)
        assertThatThrownBy {
            Lexer("y /* never closed").tokenize()
        }.hasMessageContaining("Unterminated block comment at line 1 col 3")
    }

    @Test
    fun `string escapes decode to their characters`() {
        assertThat(lexemes("""'a\\b\tc\nd\"e'""")).containsExactly("a\\b\tc\nd\"e")
    }

    @Test
    fun `numbers take a signed exponent and a dot needs a following digit`() {
        assertThat(lexemes("1.5e+3")).containsExactly("1.5e+3")
        assertThat(lexemes("2.x")).first().isEqualTo("2")
    }

    @Test
    fun `malformed strings and stray characters are lexing errors`() {
        assertThatThrownBy { Lexer("'abc").tokenize() }.hasMessageContaining("Unterminated string at line 1 col 1")
        assertThatThrownBy { Lexer("'ab\ncd'").tokenize() }.hasMessageContaining("Unterminated string at line 1 col 1")
        assertThatThrownBy { Lexer("'a\\qb'").tokenize() }.hasMessageContaining("Unknown string escape '\\q'")
        assertThatThrownBy {
            Lexer(
                "x ~ y",
            ).tokenize()
        }.hasMessageContaining("Unrecognized character '~' at line 1 col 3")
    }
}
