package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `qkt editor install` paths that never write the install manifest, so they run even where `XDG_CONFIG_HOME`
 * points the manifest at the real config home: Zed's printed steps, and a Vim sideload refused because a plugin
 * manager is present and the shell is not interactive.
 */
class EditorCommandNoManifestTest {
    @TempDir
    lateinit var home: Path

    private fun editor(vararg argv: String) =
        withUserHome(home) { captureCliRun { EditorCommand(Args(arrayOf("editor", *argv))).run() } }

    @Test
    fun `install zed prints the in-editor steps and places nothing`() {
        val run = editor("install", "ZED")

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out)
            .contains("qkt editor: Zed installs extensions from inside the editor:")
            .contains("Not listed yet? Run zed: install dev extension and choose ")
        Files.list(home).use { assertThat(it.toList()).isEmpty() }
    }

    @Test
    fun `uninstall zed points at zed's own settings`() {
        val run = editor("uninstall", "zed")

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out).contains("remove qkt from Zed in its own settings; qkt placed no files there")
    }

    @Test
    fun `a vim plugin manager without yes skips the sideload in a non-interactive shell`() {
        Files.createDirectories(home.resolve(".vim/autoload"))
        Files.writeString(home.resolve(".vim/autoload/plug.vim"), "\" vim-plug lazy.nvim packer\n")

        val run = editor("install", "vim")

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out)
            .contains("detected plugin manager(s) in your Vim config: lazy.nvim, packer.nvim, vim-plug")
            .contains("{ \"elitekaycy/qkt\", ft = \"qkt\",")
            .contains("use { \"elitekaycy/qkt\", ft = \"qkt\", rtp = \"editor/nvim\" }")
            .contains("Plug 'elitekaycy/qkt', { 'rtp': 'editor/nvim', 'for': 'qkt' }")
            .contains("Non-interactive shell and --yes not given — skipping Vim.")
        assertThat(home.resolve(".vim/syntax")).doesNotExist()
    }

    @Test
    fun `an unknown install target is refused before anything is detected`() {
        val run = editor("install", "notepad")

        assertThat(run.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(run.err).contains("qkt: unknown target 'notepad' (expected: ").contains(", all)")
    }
}
