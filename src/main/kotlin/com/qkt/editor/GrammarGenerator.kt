package com.qkt.editor

/**
 * Generates the editor grammars from the language implementation: keywords by
 * [com.qkt.dsl.parse.KeywordCategory], indicators, functions, constants and member fields from
 * [GrammarVocabulary]. `qkt editor grammar --format textmate|vim` prints the output, and the
 * checked-in files under `editor/` must equal it (see `GrammarFilesTest`).
 */
object GrammarGenerator {
    /** The grammar dialects the generator emits. */
    enum class Format(
        val cliName: String,
    ) {
        TEXTMATE("textmate"),
        VIM("vim"),
        ;

        companion object {
            /** The format spelled [name] on the command line, or null. */
            fun parse(name: String): Format? = entries.firstOrNull { it.cliName.equals(name, ignoreCase = true) }
        }
    }

    /** The TextMate JSON grammar, pretty-printed with a trailing newline. */
    fun textMate(): String = TextMateGrammar.render()

    /** The Vim syntax file. */
    fun vim(): String = VimSyntax.render()

    /** The grammar for [format]. */
    fun render(format: Format): String =
        when (format) {
            Format.TEXTMATE -> textMate()
            Format.VIM -> vim()
        }
}
