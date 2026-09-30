package com.qkt.cli.editor

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EditorTargetTest {
    @Test
    fun `parse recognizes canonical names`() {
        assertThat(EditorTarget.parse("vscode")).isEqualTo(EditorTarget.VSCODE)
        assertThat(EditorTarget.parse("nvim")).isEqualTo(EditorTarget.NVIM)
        assertThat(EditorTarget.parse("vim")).isEqualTo(EditorTarget.VIM)
    }

    @Test
    fun `parse recognizes aliases`() {
        assertThat(EditorTarget.parse("code")).isEqualTo(EditorTarget.VSCODE)
        assertThat(EditorTarget.parse("vs-code")).isEqualTo(EditorTarget.VSCODE)
        assertThat(EditorTarget.parse("neovim")).isEqualTo(EditorTarget.NVIM)
    }

    @Test
    fun `parse is case-insensitive`() {
        assertThat(EditorTarget.parse("VSCODE")).isEqualTo(EditorTarget.VSCODE)
        assertThat(EditorTarget.parse("NeoVim")).isEqualTo(EditorTarget.NVIM)
    }

    @Test
    fun `parse returns null for unknown names`() {
        assertThat(EditorTarget.parse("emacs")).isNull()
        assertThat(EditorTarget.parse("")).isNull()
        assertThat(EditorTarget.parse("intellij")).isNull()
        assertThat(EditorTarget.parse("sublime")).isNull()
    }
}
