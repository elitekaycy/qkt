package com.qkt.cli

import com.qkt.cli.editor.EditorManifest
import com.qkt.cli.editor.EditorTarget
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Drives `qkt editor` with `user.home` pointed at a temp dir. Installs write the manifest under the
 * config home, so they only run when `XDG_CONFIG_HOME` cannot redirect it to the real one.
 */
class EditorCommandTest {
    @TempDir
    lateinit var home: Path

    private fun editor(vararg argv: String) =
        withUserHome(home) { captureCliRun { EditorCommand(Args(arrayOf("editor", *argv))).run() } }

    private fun assumeIsolatedConfigHome() =
        assumeTrue(System.getenv("XDG_CONFIG_HOME").isNullOrBlank(), "XDG_CONFIG_HOME would escape the temp home")

    private fun manifest() = EditorManifest.load(home.resolve(".config/qkt/editor-install.json"))

    @Test
    fun `list prints the bundle location and every supported target with its detection state`() {
        val run = editor("list")

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out).contains("qkt editor — bundled at:").contains("Supported targets:")
        EditorTarget.entries.forEach { assertThat(run.out).contains(it.cliName).contains(it.displayName) }
        assertThat(
            run.out
                .lines()
                .filter { it.trimStart().startsWith("vim ") }
                .single(),
        ).endsWith("[not found]")
        assertThat(editor().out).isEqualTo(run.out)
    }

    @Test
    fun `an unknown subcommand is an argument error naming the expected ones`() {
        val run = editor("reinstall")

        assertThat(run.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(run.err).contains("unknown editor subcommand 'reinstall'").contains("grammar")
    }

    @Test
    fun `install and uninstall need a known target`() {
        assertThat(editor("install").let { it.code to it.err })
            .isEqualTo(ExitCodes.ARG_ERROR to "qkt: missing target. Try: qkt editor install <${choices()}>\n")
        assertThat(editor("uninstall").err).contains("qkt editor uninstall <${choices()}>")
        val install = editor("install", "notepad")
        assertThat(install.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(install.err).contains("unknown target 'notepad'")
        val uninstall = editor("uninstall", "notepad")
        assertThat(uninstall.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(uninstall.err).contains("unknown target 'notepad'")
    }

    @Test
    fun `install vim places the plugin files and uninstall removes exactly those`() {
        assumeIsolatedConfigHome()

        val install = editor("install", "vim")

        assertThat(install.code).withFailMessage(install.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(install.out).contains("installed Vim plugin (3 files at ${home.resolve(".vim")})")
        assertThat(home.resolve(".vim/syntax/qkt.vim")).isRegularFile()
        assertThat(manifest().recordFor(EditorTarget.VIM)?.files).hasSize(3)

        val uninstall = editor("uninstall", "VIM")

        assertThat(uninstall.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(home.resolve(".vim/syntax/qkt.vim")).doesNotExist()
        assertThat(manifest().recordFor(EditorTarget.VIM)).isNull()
    }

    @Test
    fun `a vim plugin manager without yes skips the sideload in a non-interactive shell`() {
        assumeIsolatedConfigHome()
        Files.createDirectories(home.resolve(".vim/autoload"))
        Files.writeString(home.resolve(".vim/autoload/plug.vim"), "\" vim-plug lazy.nvim packer\n")

        val run = editor("install", "vim")

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out)
            .contains("detected plugin manager(s) in your Vim config: lazy.nvim, packer.nvim, vim-plug")
            .contains("lazy.nvim:")
            .contains("use { \"elitekaycy/qkt\", ft = \"qkt\", rtp = \"editor/nvim\" }")
            .contains("Plug 'elitekaycy/qkt'")
            .contains("Non-interactive shell and --yes not given — skipping Vim.")
        assertThat(home.resolve(".vim/syntax/qkt.vim")).doesNotExist()
    }

    @Test
    fun `yes proceeds with the sideload past a detected plugin manager`() {
        assumeIsolatedConfigHome()
        Files.createDirectories(home.resolve(".vim"))
        Files.writeString(home.resolve(".vim/vimrc.vim"), "call plug#begin() \" vim-plug\n")

        val run = editor("install", "vim", "--yes")

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out).contains("--yes given: proceeding with sideload.").contains("installed Vim plugin")
        assertThat(home.resolve(".vim/ftdetect/qkt.vim")).isRegularFile()
    }

    @Test
    fun `install zed prints the in-editor steps and records nothing`() {
        assumeIsolatedConfigHome()

        val run = editor("install", "zed")

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out).contains("Zed installs extensions from inside the editor").contains("zed: extensions")
        assertThat(manifest().recordFor(EditorTarget.ZED)).isNull()
    }

    @Test
    fun `uninstall without an install record fails and uninstall all with no records succeeds`() {
        assumeIsolatedConfigHome()

        val vim = editor("uninstall", "vim")
        assertThat(vim.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(vim.err).contains("no install record for Vim")

        assertThat(editor("uninstall", "all").code).isEqualTo(ExitCodes.SUCCESS)
    }

    @Test
    fun `uninstall all removes every recorded install`() {
        assumeIsolatedConfigHome()
        editor("install", "vim")

        val run = editor("uninstall", "all")

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out).contains("removed Vim plugin (3 files)")
        assertThat(manifest().installs).isEmpty()
    }

    private fun choices() = EditorTarget.entries.joinToString("|") { it.cliName } + "|all"
}
