package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditorInstallerFilesTest {
    private val repoEditorRoot: Path = Path.of("editor").toAbsolutePath()

    private fun installer(
        home: Path,
        out: StringBuilder = StringBuilder(),
    ) = EditorInstaller(
        detector = EditorDetector(env = mapOf("XDG_CONFIG_HOME" to home.resolve(".config").toString()), home = home),
        editorRoot = repoEditorRoot,
        manifestPath = home.resolve("manifest.json"),
        out = out,
        err = StringBuilder(),
        processRunner = { _, _ -> 0 },
        pathLookup = { null },
    )

    @Test
    fun `sublime install places the grammar and comment settings in the user package`(
        @TempDir home: Path,
    ) {
        val out = StringBuilder()
        val result = installer(home, out).install(EditorTarget.SUBLIME)

        val pkg = home.resolve(".config/sublime-text/Packages/User/qkt")
        assertThat(
            result?.placedFiles,
        ).containsExactly(pkg.resolve("qkt.tmLanguage"), pkg.resolve("Comments.tmPreferences"))
        assertThat(out.toString()).contains("\"command\": [\"qkt\", \"lsp\"]")
    }

    @Test
    fun `emacs install loads qkt-mode from init el and keeps the user's init`(
        @TempDir home: Path,
    ) {
        Files.createDirectories(home.resolve(".emacs.d"))
        Files.writeString(home.resolve(".emacs.d/init.el"), "(setq inhibit-startup-screen t)\n")

        installer(home).install(EditorTarget.EMACS)
        installer(home).install(EditorTarget.EMACS)

        val init = Files.readString(home.resolve(".emacs.d/init.el"))
        assertThat(init).startsWith("(setq inhibit-startup-screen t)\n")
        assertThat(init.split("(require 'qkt-mode)")).hasSize(2)
        assertThat(home.resolve(".emacs.d/qkt/qkt-mode.el")).exists()
    }

    @Test
    fun `emacs install uses dot emacs when that is the init file`(
        @TempDir home: Path,
    ) {
        Files.writeString(home.resolve(".emacs"), ";; mine\n")

        installer(home).install(EditorTarget.EMACS)

        assertThat(Files.readString(home.resolve(".emacs"))).contains(";; mine", "(require 'qkt-mode)")
    }

    @Test
    fun `emacs uninstall removes the mode and only the managed block`(
        @TempDir home: Path,
    ) {
        Files.createDirectories(home.resolve(".emacs.d"))
        Files.writeString(home.resolve(".emacs.d/init.el"), "(setq inhibit-startup-screen t)\n")
        installer(home).install(EditorTarget.EMACS)

        assertThat(installer(home).uninstall(EditorTarget.EMACS)).isTrue()

        assertThat(Files.readString(home.resolve(".emacs.d/init.el"))).isEqualTo("(setq inhibit-startup-screen t)\n")
        assertThat(home.resolve(".emacs.d/qkt/qkt-mode.el")).doesNotExist()
    }

    @Test
    fun `intellij prints the textmate bundle and language server steps and records nothing`(
        @TempDir home: Path,
    ) {
        val out = StringBuilder()
        installer(home, out).install(EditorTarget.INTELLIJ)

        assertThat(out.toString()).contains("TextMate Bundles", repoEditorRoot.resolve("vscode").toString(), "qkt lsp")
        assertThat(EditorManifest.load(home.resolve("manifest.json")).recordFor(EditorTarget.INTELLIJ)).isNull()
    }
}
