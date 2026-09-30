package com.qkt.cli

import com.qkt.dsl.DslVocabulary
import com.qkt.lsp.QktVocabulary

/**
 * `qkt dsl vocabulary [--json]` — print every name the DSL accepts, read from the same tables the
 * parser, compiler and language server use. `--json` prints schema `qkt-vocabulary-v1`
 * ([VocabularyJson]); without it, a grouped human-readable listing.
 */
class DslCommand(
    private val args: Args,
) {
    fun run(): Int =
        when (val sub = args.firstNonOption()) {
            "vocabulary" -> vocabulary()
            else -> {
                System.err.println("qkt: unknown dsl subcommand '${sub ?: ""}' (expected: vocabulary)")
                ExitCodes.ARG_ERROR
            }
        }

    private fun vocabulary(): Int {
        if (args.flag("json")) {
            println(VocabularyJson.render())
            return ExitCodes.SUCCESS
        }
        group("keywords", DslVocabulary.keywords)
        group("indicators", QktVocabulary.indicators)
        group("functions", QktVocabulary.functions)
        group("constants", QktVocabulary.constants)
        group("stream fields", QktVocabulary.streamFields)
        group("series selectors", QktVocabulary.seriesSelectors)
        for ((owner, members) in DslVocabulary.members) group("$owner members", members)
        return ExitCodes.SUCCESS
    }

    private fun group(
        title: String,
        names: List<String>,
    ) {
        println("$title (${names.size})")
        println("  " + names.joinToString(" "))
    }
}
