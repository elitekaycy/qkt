package com.qkt.editor

import com.qkt.dsl.DslVocabulary
import com.qkt.dsl.parse.KeywordCategory
import com.qkt.dsl.stdlib.Constants
import com.qkt.dsl.stdlib.FuncRegistry
import com.qkt.dsl.stdlib.IndicatorRegistry

/**
 * The names the generated grammars highlight, each list read from the implementation that
 * accepts it: keywords from [KeywordCategory], indicators and functions from the registries,
 * constants from [Constants], and every field, member, selector, registry-external indicator
 * and parser-resolved call name from [DslVocabulary] — the same tables the parser, compiler
 * and language server read, so the grammar cannot know a name the language does not.
 */
object GrammarVocabulary {
    /** Pseudo-symbols read with a dot: `POSITION.gold.qty`, `NOW.hour_utc`. All are lexer keywords. */
    val pseudoSymbols: List<String> = KeywordCategory.spellings(KeywordCategory.STATE).sorted()

    /** Indicators the compiler special-cases before consulting [IndicatorRegistry]. */
    val registryExternalIndicators: List<String> = DslVocabulary.externalIndicators

    /** Calls the parser resolves itself: calendar predicates and the rolling shorthands. */
    val parserBuiltins: List<String> = DslVocabulary.clockPredicates + DslVocabulary.rollingShorthands

    /** Every spelling that can follow a dot on a stream alias or pseudo-symbol, lowercase, sorted. */
    val memberFields: List<String> =
        (
            DslVocabulary.candleFields + DslVocabulary.metaFields + DslVocabulary.seriesSelectors +
                DslVocabulary.members.values.flatten()
        ).map { it.lowercase() }.distinct().sorted()

    /** Registered indicators plus the two compiled outside the registry, uppercase, sorted. */
    val indicators: List<String> = (IndicatorRegistry.names() + registryExternalIndicators).sorted()

    /** Registered scalar functions plus the parser's own call names, uppercase, sorted. */
    val functions: List<String> = (FuncRegistry.names() + parserBuiltins).sorted()

    /** Named numeric constants, sorted. */
    val constants: List<String> = Constants.names().sorted()

    /** The action verbs that take a stream alias as their next word. */
    val aliasTakingActions: List<String> = listOf("BUY", "SELL", "CLOSE", "RESIZE")

    /** Keywords of [category], sorted for a stable grammar. */
    fun keywords(category: KeywordCategory): List<String> = KeywordCategory.spellings(category).sorted()
}
