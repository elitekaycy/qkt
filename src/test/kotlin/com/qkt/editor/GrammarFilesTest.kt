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
}
