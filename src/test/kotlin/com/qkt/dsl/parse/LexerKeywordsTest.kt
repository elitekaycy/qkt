package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Completion offers the language's keywords. Rather than duplicate the lexer's
 * keyword/operator exclusion list (which would drift), the language server reads the
 * keyword spellings straight from the lexer. This pins that accessor: section and
 * expression keywords are present; literal and punctuation token kinds are not.
 */
class LexerKeywordsTest {
    @Test
    fun `keyword spellings include section and expression keywords`() {
        val kw = Lexer.keywordSpellings()
        assertThat(kw).contains(
            "STRATEGY",
            "SYMBOLS",
            "RULES",
            "WHEN",
            "THEN",
            "BUY",
            "SELL",
            "CROSSES",
            "ABOVE",
            "BELOW",
            "BETWEEN",
            "IS",
            "NULL",
            "AND",
            "OR",
            "NOT",
        )
    }

    @Test
    fun `keyword spellings exclude literals and punctuation`() {
        val kw = Lexer.keywordSpellings()
        assertThat(kw).doesNotContain("NUMBER", "STRING", "IDENT", "EOF", "PLUS", "DOT", "LPAREN")
    }
}
