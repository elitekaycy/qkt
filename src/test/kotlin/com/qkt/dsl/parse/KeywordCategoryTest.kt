package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KeywordCategoryTest {
    private val lexerKeywords = Lexer.keywordSpellings() - "ARROW"

    @Test
    fun `every lexer keyword except ARROW has exactly one category`() {
        val uncategorized = lexerKeywords.filter { KeywordCategory.of(it) == null }
        assertThat(uncategorized).describedAs("lexer keywords without a category").isEmpty()
    }

    @Test
    fun `no category names a spelling the lexer does not know`() {
        val extra = KeywordCategory.all().keys - lexerKeywords
        assertThat(extra).describedAs("categorized spellings that are not lexer keywords").isEmpty()
    }

    @Test
    fun `ARROW is punctuation not a keyword`() {
        assertThat(KeywordCategory.of(TokenKind.ARROW)).isNull()
        assertThat(KeywordCategory.of(TokenKind.NUMBER)).isNull()
    }

    @Test
    fun `lookup by spelling is case-insensitive like the lexer`() {
        assertThat(KeywordCategory.of("strategy")).isEqualTo(KeywordCategory.SECTION)
        assertThat(KeywordCategory.of(TokenKind.BUY)).isEqualTo(KeywordCategory.ACTION)
        assertThat(KeywordCategory.of("crosses")).isEqualTo(KeywordCategory.OPERATOR_WORD)
    }

    @Test
    fun `every category has at least one keyword and a distinct scope`() {
        for (category in KeywordCategory.entries) {
            assertThat(KeywordCategory.spellings(category)).describedAs(category.name).isNotEmpty()
        }
        assertThat(KeywordCategory.entries.map { it.textMateScope }).doesNotHaveDuplicates()
        assertThat(KeywordCategory.entries.map { it.vimGroup }).doesNotHaveDuplicates()
    }
}
