package com.qkt.cli

import com.qkt.editor.GrammarGenerator
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EditorCommandGrammarTest {
    private fun run(vararg argv: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val saved = System.out
        System.setOut(PrintStream(out, true))
        val code =
            try {
                EditorCommand(Args(arrayOf("editor", *argv))).run()
            } finally {
                System.setOut(saved)
            }
        return code to out.toString()
    }

    @Test
    fun `grammar prints the TextMate grammar by default and the Vim syntax on request`() {
        assertThat(run("grammar")).isEqualTo(ExitCodes.SUCCESS to GrammarGenerator.textMate())
        assertThat(run("grammar", "--format", "textmate")).isEqualTo(ExitCodes.SUCCESS to GrammarGenerator.textMate())
        assertThat(run("grammar", "--format", "vim")).isEqualTo(ExitCodes.SUCCESS to GrammarGenerator.vim())
    }

    @Test
    fun `an unknown grammar format is an argument error`() {
        val (code, out) = run("grammar", "--format", "sublime")
        assertThat(code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(out).isEmpty()
    }
}
