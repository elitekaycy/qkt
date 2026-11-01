package com.qkt.editor

/**
 * Generates the editor grammars from the language implementation: keywords by
 * [com.qkt.dsl.parse.KeywordCategory], indicators, functions, constants and member fields from
 * [GrammarVocabulary]. `qkt editor grammar --format <format>` prints the output, and the
 * checked-in files under `editor/` must equal it (see `GrammarFilesTest`).
 */
object GrammarGenerator {
    /** The grammar dialects the generator emits. */
    enum class Format(
        val cliName: String,
    ) {
        TEXTMATE("textmate"),
        VIM("vim"),
        TREE_SITTER("tree-sitter"),
        HELIX_HIGHLIGHTS("helix-highlights"),
        ZED_HIGHLIGHTS("zed-highlights"),
        EMACS("emacs"),
        TMLANGUAGE("tmlanguage"),
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
            Format.TREE_SITTER -> TreeSitterGrammar.render()
            Format.HELIX_HIGHLIGHTS -> TreeSitterQueries.render(TreeSitterQueries.Flavor.HELIX)
            Format.ZED_HIGHLIGHTS -> TreeSitterQueries.render(TreeSitterQueries.Flavor.ZED)
            Format.EMACS -> EmacsMode.render()
            Format.TMLANGUAGE -> PlistGrammar.render(textMate())
        }
}
