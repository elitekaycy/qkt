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
        assertThat(EditorTarget.parse("vscodium")).isEqualTo(EditorTarget.VSCODIUM)
    }

    @Test
    fun `vs code forks are their own targets that install the extension`() {
        assertThat(EditorTarget.parse("cursor")).isEqualTo(EditorTarget.CURSOR)
        assertThat(EditorTarget.parse("windsurf")).isEqualTo(EditorTarget.WINDSURF)
        assertThat(EditorTarget.parse("codium")).isEqualTo(EditorTarget.VSCODIUM)
        assertThat(EditorTarget.entries.filter { it.isVscodeFamily })
            .containsExactly(EditorTarget.VSCODE, EditorTarget.CURSOR, EditorTarget.WINDSURF, EditorTarget.VSCODIUM)
    }

    @Test
    fun `parse is case-insensitive`() {
        assertThat(EditorTarget.parse("VSCODE")).isEqualTo(EditorTarget.VSCODE)
        assertThat(EditorTarget.parse("NeoVim")).isEqualTo(EditorTarget.NVIM)
    }

    @Test
    fun `sublime, emacs and the jetbrains ides are targets`() {
        assertThat(EditorTarget.parse("subl")).isEqualTo(EditorTarget.SUBLIME)
        assertThat(EditorTarget.parse("emacs")).isEqualTo(EditorTarget.EMACS)
        assertThat(EditorTarget.parse("pycharm")).isEqualTo(EditorTarget.INTELLIJ)
        assertThat(EditorTarget.parse("intellij")).isEqualTo(EditorTarget.INTELLIJ)
    }

    @Test
    fun `helix and zed are targets`() {
        assertThat(EditorTarget.parse("helix")).isEqualTo(EditorTarget.HELIX)
        assertThat(EditorTarget.parse("hx")).isEqualTo(EditorTarget.HELIX)
        assertThat(EditorTarget.parse("zed")).isEqualTo(EditorTarget.ZED)
    }

    @Test
    fun `parse returns null for unknown names`() {
        assertThat(EditorTarget.parse("")).isNull()
        assertThat(EditorTarget.parse("notepad")).isNull()
        assertThat(EditorTarget.parse("eclipse")).isNull()
    }
}
