package com.qkt.editor

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The checked-in grammars must equal the generator's output; regenerate with `qkt editor grammar`. */
class GrammarFilesTest {
    private fun read(path: String): String = Files.readString(Path.of(path))

    @Test
    fun `the TextMate grammar and its VS Code copy equal the generated grammar`() {
        val generated = GrammarGenerator.textMate()
        assertThat(read("editor/textmate/qkt.tmLanguage.json"))
            .describedAs("editor/textmate/qkt.tmLanguage.json; regenerate with qkt editor grammar --format textmate")
            .isEqualTo(generated)
        assertThat(read("editor/vscode/syntaxes/qkt.tmLanguage.json"))
            .describedAs("editor/vscode/syntaxes/qkt.tmLanguage.json; copy from editor/textmate")
            .isEqualTo(generated)
    }

    @Test
    fun `the Vim syntax file equals the generated syntax`() {
        assertThat(read("editor/nvim/syntax/qkt.vim"))
            .describedAs("editor/nvim/syntax/qkt.vim; regenerate with qkt editor grammar --format vim")
            .isEqualTo(GrammarGenerator.vim())
    }

    @Test
    fun `the tree-sitter grammar and the Helix and Zed highlight queries equal the generated files`() {
        assertThat(read("editor/tree-sitter-qkt/grammar.js"))
            .describedAs("editor/tree-sitter-qkt/grammar.js; regenerate with qkt editor grammar --format tree-sitter")
            .isEqualTo(GrammarGenerator.render(GrammarGenerator.Format.TREE_SITTER))
        assertThat(read("editor/tree-sitter-qkt/queries/highlights.scm"))
            .describedAs("editor/tree-sitter-qkt/queries/highlights.scm; regenerate with --format helix-highlights")
            .isEqualTo(GrammarGenerator.render(GrammarGenerator.Format.HELIX_HIGHLIGHTS))
        assertThat(read("editor/zed/languages/qkt/highlights.scm"))
            .describedAs("editor/zed/languages/qkt/highlights.scm; regenerate with --format zed-highlights")
            .isEqualTo(GrammarGenerator.render(GrammarGenerator.Format.ZED_HIGHLIGHTS))
    }

    @Test
    fun `the Emacs mode and the Sublime grammar equal the generated files`() {
        assertThat(read("editor/emacs/qkt-mode.el"))
            .describedAs("editor/emacs/qkt-mode.el; regenerate with qkt editor grammar --format emacs")
            .isEqualTo(GrammarGenerator.render(GrammarGenerator.Format.EMACS))
        assertThat(read("editor/sublime/qkt.tmLanguage"))
            .describedAs("editor/sublime/qkt.tmLanguage; regenerate with qkt editor grammar --format tmlanguage")
            .isEqualTo(GrammarGenerator.render(GrammarGenerator.Format.TMLANGUAGE))
    }
}
