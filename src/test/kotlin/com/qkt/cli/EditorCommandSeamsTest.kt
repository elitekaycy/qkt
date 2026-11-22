package com.qkt.cli

import com.qkt.cli.editor.EditorDetector
import com.qkt.cli.editor.EditorManifest
import com.qkt.cli.editor.EditorTarget
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `qkt editor` against a temp home with the environment and detection injected, so nothing depends on the
 * developer's `XDG_CONFIG_HOME` or on which editors are on `PATH`.
 */
class EditorCommandSeamsTest {
    @TempDir
    lateinit var home: Path

    private fun editor(
        interactive: Boolean = false,
        stdin: String? = null,
        argv: Array<String>,
    ) = withUserHome(home) {
        captureCliRun(stdin) {
            EditorCommand(
                Args(arrayOf("editor", *argv)),
                env = emptyMap(),
                interactive = { interactive },
                detectorFor = { EditorDetector(env = it, home = home, pathLookup = { null }) },
            ).run()
        }
    }

    private fun manifest() = EditorManifest.load(home.resolve(".config/qkt/editor-install.json"))

    private fun vimWithPlugManager() {
        Files.createDirectories(home.resolve(".vim/autoload"))
        Files.writeString(home.resolve(".vim/autoload/plug.vim"), "\" vim-plug\n")
    }

    @Test
    fun `install all installs for every detected editor and says which`() {
        Files.createDirectories(home.resolve(".vim"))

        val run = editor(argv = arrayOf("install", "all"))

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out).contains("qkt editor: installing for detected editors: vim")
        assertThat(home.resolve(".vim/syntax/qkt.vim")).isRegularFile()
        assertThat(manifest().recordFor(EditorTarget.VIM)).isNotNull()
    }

    @Test
    fun `uninstall all removes the installs the manifest records`() {
        Files.createDirectories(home.resolve(".vim"))
        editor(argv = arrayOf("install", "vim"))
        assertThat(manifest().recordFor(EditorTarget.VIM)).isNotNull()

        val run = editor(argv = arrayOf("uninstall", "all"))

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(home.resolve(".vim/syntax/qkt.vim")).doesNotExist()
        assertThat(manifest().recordFor(EditorTarget.VIM)).isNull()
    }

    @Test
    fun `yes sideloads past a plugin manager without asking`() {
        vimWithPlugManager()

        val run = editor(argv = arrayOf("install", "vim", "--yes"))

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out).contains("--yes given: proceeding with sideload.")
        assertThat(home.resolve(".vim/syntax/qkt.vim")).isRegularFile()
    }

    @Test
    fun `an interactive yes sideloads and anything else skips`() {
        vimWithPlugManager()

        val declined = editor(interactive = true, stdin = "n\n", argv = arrayOf("install", "vim"))
        assertThat(declined.out).contains("Continue with sideload anyway? [y/N]").contains("Skipping Vim.")
        assertThat(home.resolve(".vim/syntax/qkt.vim")).doesNotExist()

        val accepted = editor(interactive = true, stdin = " Yes \n", argv = arrayOf("install", "vim"))
        assertThat(accepted.out).contains("Continue with sideload anyway? [y/N]").doesNotContain("Skipping Vim.")
        assertThat(home.resolve(".vim/syntax/qkt.vim")).isRegularFile()
    }
}
