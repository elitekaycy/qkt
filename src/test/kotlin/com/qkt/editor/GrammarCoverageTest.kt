package com.qkt.editor

import com.qkt.dsl.parse.KeywordCategory
import com.qkt.dsl.parse.Lexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GrammarCoverageTest {
    private val probe = TextMateProbe(GrammarGenerator.textMate())
    private val vim = GrammarGenerator.vim()

    @Test
    fun `every lexer keyword is matched by its category's TextMate rule and appears in the Vim syntax`() {
        val keywords = Lexer.keywordSpellings() - "ARROW"
        val unmatched =
            keywords.filter { word ->
                val scope = KeywordCategory.of(word)?.textMateScope
                scope == null || !probe.matches(scope, word) || !probe.matches(scope, word.lowercase())
            }
        assertThat(unmatched).describedAs("keywords without a matching rule").isEmpty()
        assertThat(keywords.filter { "\\|$it\\|" !in vim && "\\%($it\\|" !in vim && "\\|$it\\)" !in vim })
            .describedAs("keywords missing from the Vim syntax")
            .isEmpty()
        assertThat(keywords).hasSize(153)
    }

    @Test
    fun `every indicator, function and constant is matched`() {
        assertThat(GrammarVocabulary.indicators).hasSize(61)
        assertThat(GrammarVocabulary.functions).hasSize(22)
        assertThat(
            GrammarVocabulary.indicators.filter {
                !probe.matches("support.function.indicator.qkt", "${it.lowercase()}(")
            },
        ).isEmpty()
        assertThat(GrammarVocabulary.functions.filter { !probe.matches("support.function.builtin.qkt", "$it (") })
            .isEmpty()
        assertThat(GrammarVocabulary.constants.filter { !probe.matches("constant.language.qkt", it) }).isEmpty()
        assertThat(
            (GrammarVocabulary.indicators + GrammarVocabulary.functions + GrammarVocabulary.constants).filter {
                it !in
                    vim
            },
        ).describedAs("names missing from the Vim syntax")
            .isEmpty()
    }

    @Test
    fun `every pseudo-symbol is a language variable and every member field is a field`() {
        assertThat(
            GrammarVocabulary.pseudoSymbols.filter {
                it !in setOf("SEQUENCE") &&
                    probe.scopeOf("$it.") != "variable.language.qkt"
            },
        ).describedAs("pseudo-symbols not scoped variable.language")
            .isEmpty()
        assertThat(probe.scopeOf("SEQUENCE.setup.stage")).isEqualTo("keyword.control.section.qkt")
        assertThat(
            GrammarVocabulary.memberFields.filter { !probe.matches("support.variable.field.qkt", ".$it") },
        ).isEmpty()
        assertThat(GrammarVocabulary.memberFields.filter { it !in vim }).isEmpty()
    }

    @Test
    fun `ARROW is an operator not a keyword`() {
        assertThat(probe.scopeOf("->")).isEqualTo("keyword.operator.qkt")
        assertThat(probe.scopeOf("ARROW")).isNull()
    }
}
