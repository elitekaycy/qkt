package com.qkt.editor

import com.qkt.dsl.parse.KeywordCategory
import com.qkt.dsl.parse.operatorToken
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TreeSitterGrammarTest {
    private val grammar = TreeSitterGrammar.render()

    @Test
    fun `every lexer keyword is a keyword of its category`() {
        for (category in KeywordCategory.entries) {
            val line = grammar.lines().single { it.trimStart().startsWith("${TreeSitterGrammar.nodeType(category)}:") }
            for (keyword in GrammarVocabulary.keywords(category)) {
                assertThat(line).describedAs("$keyword in ${category.name}").contains("'$keyword'")
            }
        }
    }

    @Test
    fun `every operator and punctuation character the lexer accepts has a token`() {
        val tokens = TreeSitterGrammar.OPERATORS + TreeSitterGrammar.PUNCTUATION
        for (c in '!'..'~') {
            for (next in listOf(' ', '=', '>', '-')) {
                val lexeme = operatorToken(c, next)?.second ?: continue
                assertThat(tokens).describedAs("lexeme $lexeme").contains(lexeme)
            }
        }
    }

    @Test
    fun `each editor's queries capture every keyword category`() {
        for (flavor in TreeSitterQueries.Flavor.entries) {
            val queries = TreeSitterQueries.render(flavor)
            for (category in KeywordCategory.entries) {
                assertThat(queries).contains("(${TreeSitterGrammar.nodeType(category)}) @")
            }
        }
    }
}
